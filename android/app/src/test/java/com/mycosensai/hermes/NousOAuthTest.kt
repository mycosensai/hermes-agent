package com.mycosensai.hermes

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant

class NousOAuthTest {
    @get:Rule val temp = TemporaryFolder()
    private var clock = 1700000000000L
    private data class Call(val path: String, val fields: Map<String,String>, val bearer: String?, val refresh: String?, val json: Boolean)
    private class Fake : OAuthTransport {
        val results = java.util.ArrayDeque<Any>()
        val calls = mutableListOf<Call>()
        override fun post(path: String, fields: Map<String,String>, bearer: String?, refresh: String?, json: Boolean): HttpResult {
            calls.add(Call(path, fields, bearer, refresh, json))
            val result = results.removeFirst()
            if (result is IOException) throw result
            return result as HttpResult
        }
        fun add(status: Int, body: String) { results.add(HttpResult(status, JSONObject(body))) }
    }
    private fun device(fake: Fake, ttl: Int = 600) {
        fake.add(200, """{"device_code":"device-test","user_code":"TEST-CODE","verification_uri":"https://portal.nousresearch.com/verify","expires_in":$ttl,"interval":5}""")
    }
    private fun token(fake: Fake, scope: String = "inference:invoke", refresh: String = "refresh-one") {
        fake.add(200, """{"access_token":"access-test","refresh_token":"$refresh","expires_in":3600,"scope":"$scope"}""")
    }
    private fun state(home: File) = JSONObject(File(home,"auth.json").readText()).getJSONObject("providers").getJSONObject("nous")
    private fun oauth(home: File, fake: Fake) = NousOAuth(home, fake) { clock }

    @Test fun dnsFailureDoesNotAbandonApprovalAndPendingSurvivesRecreation() {
        val home = temp.newFolder(); val fake = Fake(); val first = oauth(home, fake)
        device(fake); first.start()
        fake.results.add(UnknownHostException("test DNS unavailable"))
        assertEquals("retry", first.poll().getString("status"))
        assertNotNull(first.pendingInfo())
        val restored = oauth(home, fake)
        assertEquals("TEST-CODE", restored.pendingInfo()!!.getString("user_code"))
        fake.add(400, """{"error":"authorization_pending"}""")
        assertEquals("pending", restored.poll().getString("status"))
        token(fake)
        assertEquals("approved", restored.poll().getString("status"))
        assertTrue(restored.status()!!.getBoolean("signed_in"))
        assertNull(restored.pendingInfo())
        assertEquals("access-test", state(home).getString("agent_key"))
        val shared = JSONObject(File(home,"shared/nous_auth.json").readText())
        assertEquals("refresh-one", shared.getString("refresh_token"))
        assertFalse(shared.has("agent_key"))
        assertEquals("/api/oauth/token", fake.calls.last().path)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", fake.calls.last().fields["grant_type"])
    }
    @Test fun slowDownPersistsIncreasedInterval() {
        val home = temp.newFolder(); val fake=Fake(); val auth=oauth(home,fake)
        device(fake); auth.start(); fake.add(400, """{"error":"slow_down"}""")
        assertEquals(10,auth.poll().getInt("interval"))
        assertEquals(10,oauth(home,fake).pendingInfo()!!.getInt("interval"))
    }
    @Test fun serverOutageIsRetriableWithoutDiscardingCode() {
        val fake=Fake(); val auth=oauth(temp.newFolder(),fake)
        device(fake); auth.start(); fake.add(503, "{}")
        assertEquals("retry",auth.poll().getString("status")); assertNotNull(auth.pendingInfo())
    }
    @Test fun denialIsTerminal() {
        val fake=Fake(); val auth=oauth(temp.newFolder(),fake)
        device(fake); auth.start(); fake.add(400,"""{"error":"access_denied"}""")
        assertEquals("error",auth.poll().getString("status")); assertNull(auth.pendingInfo())
    }
    @Test fun expiryStopsWithoutAnotherRequest() {
        val fake=Fake(); val auth=oauth(temp.newFolder(),fake)
        device(fake,1); auth.start(); clock += 2000
        assertEquals("expired",auth.poll().getString("status")); assertEquals(1,fake.calls.size)
    }
    @Test fun mintFailureDoesNotLoseApprovedSession() {
        val home=temp.newFolder(); val fake=Fake(); val auth=oauth(home,fake)
        device(fake); auth.start(); token(fake,"inference:mint_agent_key")
        fake.results.add(UnknownHostException("test"))
        val result=auth.poll()
        assertEquals("approved",result.getString("status"))
        assertTrue(result.getString("summary").startsWith("Signed in"))
        assertEquals("refresh-one",state(home).getString("refresh_token"))
        assertNull(auth.pendingInfo())
    }
    @Test fun rotationIsCommittedBeforeMintAndNeverReusesOldRefreshToken() {
        val home=temp.newFolder(); val fake=Fake(); val auth=oauth(home,fake)
        device(fake); auth.start(); token(fake); auth.poll(); clock += 3600000
        token(fake,"inference:mint_agent_key","refresh-rotated")
        fake.results.add(UnknownHostException("test mint DNS failure"))
        try { auth.ensureReady(); fail("mint should fail") } catch (_: IllegalStateException) { }
        assertEquals("refresh-rotated",state(home).getString("refresh_token"))
        assertEquals("refresh-rotated",JSONObject(File(home,"shared/nous_auth.json").readText()).getString("refresh_token"))
        val refresh = fake.calls[fake.calls.size-2]
        assertEquals("refresh-one",refresh.refresh)
        assertEquals("refresh_token",refresh.fields["grant_type"])
        assertFalse(refresh.fields.containsKey("refresh_token"))
        fake.add(200,"""{"api_key":"minted-key","expires_in":3600,"key_id":"test-id"}""")
        assertTrue(auth.ensureReady())
        assertEquals("/api/oauth/agent-key",fake.calls.last().path)
        assertEquals("access-test",fake.calls.last().bearer)
        assertTrue(fake.calls.last().json)
        assertEquals("minted-key",state(home).getString("agent_key"))
    }
    @Test fun concurrentEnsureReadyUsesOneRotation() {
        val home=temp.newFolder();val fake=Fake();val auth=oauth(home,fake)
        device(fake);auth.start();token(fake);auth.poll();clock += 3600000;token(fake,refresh="rotated")
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks=(1..2).map { pool.submit<Boolean> { auth.ensureReady() } }
            tasks.forEach { assertTrue(it.get()) }
        } finally { pool.shutdownNow() }
        assertEquals(1,fake.calls.count { it.fields["grant_type"] == "refresh_token" })
    }
    @Test fun scopeFallbackWithoutTokenScopeStillMintsInferenceKey() {
        val home=temp.newFolder();val fake=Fake();val auth=oauth(home,fake)
        fake.add(400,"""{"error":"invalid_scope"}""");device(fake);auth.start()
        assertEquals("inference:mint_agent_key",fake.calls.last().fields["scope"])
        fake.add(200,"""{"access_token":"access-test","refresh_token":"refresh","expires_in":3600}""")
        fake.add(200,"""{"api_key":"minted","expires_at":null,"expires_in":3600}""")
        assertEquals("approved",auth.poll().getString("status"))
        assertEquals("minted",state(home).getString("agent_key"))
        assertTrue(Instant.parse(state(home).getString("agent_key_expires_at")).toEpochMilli() > clock)
    }
    @Test fun nullAccessTokenIsRejectedWithoutPersisting() {
        val home=temp.newFolder();val fake=Fake();val auth=oauth(home,fake)
        device(fake);auth.start();fake.add(200,"""{"access_token":null}""")
        try {auth.poll();fail("missing access token")} catch (_:IllegalArgumentException) { }
        assertFalse(File(home,"auth.json").exists())
    }
    @Test fun platformTransportRejectsUntrustedHostsBeforeConnecting() {
        try { NativeHttp.request("https://example.com/token","POST","{}","");fail("untrusted host") }
        catch (_: IllegalArgumentException) { }
        try { NativeHttp.request("http://portal.nousresearch.com/token","POST","{}","");fail("plaintext") }
        catch (_: IllegalArgumentException) { }
    }
}
