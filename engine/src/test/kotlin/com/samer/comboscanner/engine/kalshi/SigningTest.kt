package com.samer.comboscanner.engine.kalshi

import com.samer.comboscanner.engine.Leg
import com.samer.comboscanner.engine.Side
import com.samer.comboscanner.engine.odds.LegKind
import com.samer.comboscanner.engine.odds.LegMatcher
import com.samer.comboscanner.engine.odds.OddsBookmaker
import com.samer.comboscanner.engine.odds.OddsEvent
import com.samer.comboscanner.engine.odds.OddsMarket
import com.samer.comboscanner.engine.odds.OddsOutcome
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SigningTest {
    @Test fun signsWithRsaPssThatVerifies() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----"
        val key = KalshiClient.parsePrivateKey(pem)
        val msg = "1703123456789GET/trade-api/v2/portfolio/balance"
        val sig = KalshiClient.sign(key, msg)
        val v = Signature.getInstance("RSASSA-PSS").apply {
            setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
            initVerify(kp.public)
            update(msg.toByteArray())
        }
        assertTrue(v.verify(Base64.getDecoder().decode(sig)))
    }

    @Test fun loadsPkcs1Keys() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        // PKCS#8 = header + PKCS#1; strip the 26-byte header for a 2048-bit key to get raw PKCS#1.
        val pkcs8 = kp.private.encoded
        val pkcs1 = pkcs8.copyOfRange(26, pkcs8.size)
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs1) + "\n-----END RSA PRIVATE KEY-----"
        assertEquals(kp.private, KalshiClient.parsePrivateKey(pem))
    }
}

class MatcherTest {
    private fun ev(threeWay: Boolean) = OddsEvent(
        "e", "soccer_epl", "EPL", "2026-11-24T20:00:00Z", "Arsenal", "Chelsea",
        listOf(
            OddsBookmaker("pinnacle", markets = listOf(
                OddsMarket("h2h", outcomes = listOfNotNull(OddsOutcome("Arsenal", 1.9), OddsOutcome("Chelsea", 4.2), if (threeWay) OddsOutcome("Draw", 3.6) else null)),
                OddsMarket("totals", outcomes = listOf(OddsOutcome("Over", 1.25, 1.5), OddsOutcome("Under", 3.9, 1.5))),
            )),
        ),
    )

    private fun leg(outcome: String, side: Side = Side.YES) =
        Leg("M", "E", "S", "C", side, "Arsenal vs. Chelsea", outcome, "Soccer", null, 80, null, 0)

    @Test fun parsesTotalsAndNegation() {
        assertEquals(LegKind.Total(true, 1.5), LegMatcher.parse(leg("Over 1.5 goals"), ev(true)))
        assertEquals(LegKind.Total(false, 1.5), LegMatcher.parse(leg("Not: Over 1.5 goals", Side.NO), ev(true)))
    }

    @Test fun threeWayNoOnTeamIsUnmatched() {
        assertEquals(null, LegMatcher.parse(leg("Not: Arsenal", Side.NO), ev(true), threeWay = true))
        assertEquals(LegKind.Winner("Chelsea"), LegMatcher.parse(leg("Not: Arsenal", Side.NO), ev(false), threeWay = false))
    }

    @Test fun matchesTotalsMarket() {
        val m = LegMatcher.match(leg("Over 1.5 goals"), listOf(ev(true)))
        assertNotNull(m)
        assertEquals(0, m.books.single().targetIndex)
    }
}
