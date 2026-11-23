# Kalshi Combo Scanner

Android app that finds mispriced Kalshi combo legs, builds 4–7 leg combos from them, and tests
RFQ quotes across groupings, entry order and stake. Purchases stay manual in the Kalshi app.

## How a scan works (Scan now)

1. **Legs.** Lists every open combo collection (`GET /multivariate_event_collections`) and every market
   in its eligible events. Each side becomes a leg priced at its current ask.
2. **Fair probability.** Sportsbook consensus from The Odds API (margin removed, sharp books weighted
   higher) blended with a historical base rate from settled Kalshi markets in the same series. The
   Kalshi price is never used as evidence. Each estimate carries a 90% interval.
3. **Filter and rank.** Keeps legs with estimated fair probability ≥ 80% (adjustable) and positive edge after
   Kalshi's taker fee (`ceil(0.07 × C × P × (1−P))`), ranked by expected return per dollar.
4. **Combos.** Builds 4–7 leg groupings from the strongest legs in rank order (A+B+C+D, A+B+C+E, …),
   one leg per game by default.
5. **Quotes** (official API, RFQs are closed straight after reading, never accepted):
   - first quotes on different groupings and lengths at the lowest stake;
   - entry order (A→B→C→D vs C→A→D→B) at the same stake, skipped when the collection says order
     doesn't matter or both orders price the same;
   - stake sweep with the order fixed, including $1 neighbours ($5, $6, $10, $11);
   - A–B–A rechecks on any difference, and a final recheck of the leader before it's marked verified
     or alerted.
6. **Results.** Combos ranked by estimated return per dollar, with expected profit, offered vs fair
   multiplier, and the payout improvement over the individual Kalshi prices (unavailable for related legs).

## Setup

- Install the APK from the latest **Build** workflow run (Actions → Build → `combo-scanner-apk`).
- The app opens with **Demo data** on so you can look around without keys.
- Settings (gear icon): add your Kalshi API key ID and private key, and an Odds API key, then turn
  Demo data off.

## Development

```
SKIP_ANDROID=1 ./gradlew :engine:test   # engine only, no Android SDK needed
./gradlew :app:assembleRelease          # full app (needs the Android SDK)
```

`engine/` is plain Kotlin (all pricing, combo and quote logic, Kalshi and Odds API clients).
`app/` is the Jetpack Compose UI, storage and background alerts.

Kalshi's developer agreement limits API use to your own trading and doesn't allow sharing the data,
so this app is for personal use.
