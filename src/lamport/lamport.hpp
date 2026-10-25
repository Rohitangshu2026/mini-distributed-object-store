// Lamport timestamps across independently clocked feed handler instances.
//
// Two instances handle the same stream one after the other: instance 0
// hands it over to instance 1 with a message. Each instance logs its book
// updates with two timestamps, its own physical clock (which may be skewed)
// and its Lamport clock. Merging the two logs by physical time can put
// updates out of order; merging by Lamport time cannot, because the handoff
// message makes everything instance 0 did "happen before" everything
// instance 1 does.

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "exchange/exchange.hpp"
#include "handler/feed_handler.hpp"

using namespace std;

// Lamport's logical clock (Lecture 2): tick before every local or send event;
// on receiving a message stamped m, jump to max(own, m) + 1. So if event a
// happens before event b, then L(a) < L(b), whatever the physical clocks say.
class LamportClock{
 public:
  uint64_t tick(){ return ++time_; }
  uint64_t receive(uint64_t stamp){
    time_ = max(time_, stamp) + 1;
    return time_;
  }

 private:
  uint64_t time_ = 0;
};

// One line of an instance's log: its book moved to `seq`.
struct LogEntry{
  int instance;
  uint64_t seq;
  uint64_t lamport;
  int64_t physical_us;  // the instance's own clock
};

// A feed handler with its own physical clock, `clock_offset_us` away from
// true time, and a Lamport clock. Each book update is a local event: it
// ticks the Lamport clock and is logged with both timestamps.
class HandlerInstance{
 public:
  HandlerInstance(int id, int64_t clock_offset_us, const Exchange& exchange);

  // Feeds one event at true time `now_us`, and logs the update if the book moved.
  void deliver(const Event& event, int64_t now_us);

  // Sends the handoff message: a send event. Returns its Lamport stamp.
  uint64_t hand_off(){ return clock_.tick(); }

  // Receives the handoff message at `now_us` and takes over the stream from a
  // snapshot, which is the instance's first update.
  void take_over(uint64_t handoff_stamp, int64_t now_us);

  const vector<LogEntry>& log() const{ return log_; }

 private:
  void log_update(int64_t now_us);

  int id_;
  int64_t clock_offset_us_;
  LamportClock clock_;
  FeedHandler handler_;
  vector<LogEntry> log_;
};

// Merges the instances' logs, ordered by physical time or by (Lamport time,
// instance id). Ties keep log order.
vector<LogEntry> merge_by_physical_time(vector<LogEntry> entries);
vector<LogEntry> merge_by_lamport_time(vector<LogEntry> entries);

// Updates whose seq is lower than one merged before them: places where the
// merged log shows the book going backwards.
size_t count_order_violations(const vector<LogEntry>& merged);

struct LamportDemoResult{
  size_t updates = 0;
  size_t physical_violations = 0;
  size_t lamport_violations = 0;
};

// Runs the handoff. The exchange publishes one event per millisecond.
// Instance 0 handles the first half of the events, then hands over; the
// message takes `handoff_latency_us` to arrive. Instance 0's clock runs
// `skew_us` ahead of instance 1's. Physical time misorders updates once the
// skew exceeds the handoff latency; Lamport time never does.
LamportDemoResult run_lamport_demo(int64_t skew_us, int64_t handoff_latency_us, size_t events = 200,
                                   uint64_t seed = 1);
