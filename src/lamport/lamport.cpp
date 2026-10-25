#include "lamport/lamport.hpp"

#include <tuple>

using namespace std;

namespace{

constexpr int64_t event_interval_us = 1'000;  // the exchange publishes one event per millisecond
constexpr size_t reorder_window = 3;

}  // namespace

HandlerInstance::HandlerInstance(int id, int64_t clock_offset_us, const Exchange& exchange) : id_(id), clock_offset_us_(clock_offset_us), handler_(exchange, reorder_window){}

void HandlerInstance::deliver(const Event& event, int64_t now_us){
  const uint64_t before = handler_.last_applied();
  handler_.on_event(event);
  if (handler_.last_applied() != before){
    log_update(now_us);
  }
}

void HandlerInstance::take_over(uint64_t handoff_stamp, int64_t now_us){
  clock_.receive(handoff_stamp);
  handler_.resync();
  log_update(now_us);
}

void HandlerInstance::log_update(int64_t now_us){
  log_.push_back({id_, handler_.last_applied(), clock_.tick(), now_us + clock_offset_us_});
}

vector<LogEntry> merge_by_physical_time(vector<LogEntry> entries){
  stable_sort(entries.begin(), entries.end(), [](const LogEntry& a, const LogEntry& b){
    return tie(a.physical_us, a.instance) < tie(b.physical_us, b.instance);
  });
  return entries;
}

vector<LogEntry> merge_by_lamport_time(vector<LogEntry> entries){
  stable_sort(entries.begin(), entries.end(), [](const LogEntry& a, const LogEntry& b){
    return tie(a.lamport, a.instance) < tie(b.lamport, b.instance);
  });
  return entries;
}

size_t count_order_violations(const vector<LogEntry>& merged){
  size_t violations = 0;
  uint64_t highest_seq = 0;
  for (const LogEntry& entry : merged){
    if (entry.seq < highest_seq){
      ++violations;
    }
    highest_seq = max(highest_seq, entry.seq);
  }
  return violations;
}

LamportDemoResult run_lamport_demo(int64_t skew_us, int64_t handoff_latency_us, size_t events, uint64_t seed){
  Exchange exchange(seed, events);
  HandlerInstance first(0, skew_us, exchange);  // its clock runs skew_us ahead
  HandlerInstance second(1, 0, exchange);
  size_t sent = 0;
  int64_t now_us = 0;

  // Instance 0 handles the first half of the stream.
  for (; sent < events / 2; ++sent){
    now_us += event_interval_us;
    first.deliver(exchange.next(), now_us);
  }

  // Instance 0 hands over. While the message is in flight the exchange keeps
  // publishing; instance 1 picks those events up through its snapshot.
  const uint64_t stamp = first.hand_off();
  const int64_t arrival_us = now_us + handoff_latency_us;
  for (; sent < events && now_us + event_interval_us <= arrival_us; ++sent){
    now_us += event_interval_us;
    exchange.next();
  }
  second.take_over(stamp, arrival_us);

  // Instance 1 handles the rest.
  for (; sent < events; ++sent){
    now_us += event_interval_us;
    second.deliver(exchange.next(), now_us);
  }

  vector<LogEntry> entries = first.log();
  entries.insert(entries.end(), second.log().begin(), second.log().end());
  LamportDemoResult result;
  result.updates = entries.size();
  result.physical_violations = count_order_violations(merge_by_physical_time(entries));
  result.lamport_violations = count_order_violations(merge_by_lamport_time(entries));
  return result;
}
