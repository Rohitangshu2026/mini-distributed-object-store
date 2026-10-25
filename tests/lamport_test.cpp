// Tests for the Lamport clock and the handoff between two independently
// clocked handler instances.

#include <algorithm>
#include <cstdint>
#include <string>

#include <gtest/gtest.h>

#include "lamport/lamport.hpp"

using namespace std;

namespace{

TEST(Lamport, ClockFollowsLamportsRule){
  LamportClock clock;
  EXPECT_EQ(clock.tick(), 1u);
  EXPECT_EQ(clock.receive(10), 11u);  // max(1, 10) + 1
  EXPECT_EQ(clock.receive(3), 12u);   // max(11, 3) + 1
  EXPECT_EQ(clock.tick(), 13u);
}

TEST(Lamport, OnlyPhysicalTimeMisordersUpdatesWhenSkewExceedsTheHandoff){
  constexpr int64_t handoff_latency_us = 5'000;
  for (int64_t skew_ms = 0; skew_ms <= 20; ++skew_ms){
    const LamportDemoResult result = run_lamport_demo(skew_ms * 1'000, handoff_latency_us);
    SCOPED_TRACE("skew " + to_string(skew_ms) + " ms");
    EXPECT_EQ(result.lamport_violations, 0u);
    // About one misordered update per millisecond of skew beyond the handoff.
    const int64_t expected = max<int64_t>(0, skew_ms - handoff_latency_us / 1'000);
    EXPECT_EQ(result.physical_violations, static_cast<size_t>(expected));
  }
}

}  // namespace
