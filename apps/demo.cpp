// gapless_demo [scenario]
//
// Runs the feed handler against an exchange through a lossy link and prints
// what happens. Each scenario shows one behaviour:
//
//   burst    5 events lost in a row; recovered by retransmission
//   reorder  events arrive out of order or twice; absorbed, no recovery needed
//   tail     the last events are lost; the heartbeat exposes them
//   outage   a gap older than the exchange's history; recovered by snapshot
//   random   [events] [loss %] [seed]: random damage at scale
//   lamport  two instances with skewed clocks hand the stream over
//   all      every scenario in turn (the default)
//
// Every feed handler scenario ends by checking the handler's book against
// the exchange's own book. The exit code is non-zero if any check fails.

#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <optional>
#include <string>

#include "exchange/exchange.hpp"
#include "handler/feed_handler.hpp"
#include "lamport/lamport.hpp"
#include "network/lossy_link.hpp"

using namespace std;

namespace{

constexpr size_t reorder_window = 3;  // a gap still open after 3 more arrivals is loss

string describe(const optional<Level>& level){
  return level ? to_string(level->price) + " x " + to_string(level->quantity) : "none";
}

// Feeds arriving events to the handler and prints what it did with each.
// Quiet mode prints only what is not a plain in-order apply.
class Tracer{
 public:
  Tracer(FeedHandler& handler, bool quiet) : handler_(handler), quiet_(quiet){}

  void arrive(const Event& event){
    const uint64_t applied_before = handler_.last_applied();
    const HandlerStats before = handler_.stats();
    handler_.on_event(event);
    const HandlerStats& after = handler_.stats();
    const uint64_t applied = handler_.last_applied();

    string what;
    if (after.duplicates > before.duplicates){
      what = "duplicate, dropped";
    } else if (after.snapshots > before.snapshots){
      what = missing() + " still missing, too old to retransmit: snapshot, book now at seq " + to_string(applied);
    } else if (after.retransmits > before.retransmits){
      what = missing() + " still missing after " + to_string(reorder_window) +
             " more arrivals: retransmitted, book now at seq " + to_string(applied);
    } else if (after.gaps > before.gaps){
      missing_first_ = applied_before + 1;
      missing_last_ = event.seq - 1;
      what = "early, buffered: gap opens, " + missing() + " missing";
    } else if (applied > applied_before + 1){
      const uint64_t drained = applied - applied_before - 1;
      what = "late, fills the gap: applied, plus " + to_string(drained) + " buffered event" + (drained == 1 ? "" : "s") +
             ", book now at seq " + to_string(applied);
    } else if (applied == applied_before + 1){
      if (quiet_){
        return;
      }
      what = "applied, book at seq " + to_string(applied);
    } else{
      what = "early, buffered (" + to_string(handler_.buffered()) + " waiting)";
    }
    cout << "  seq " << setw(3) << event.seq << " arrives: " << what << "\n";
  }

  void heartbeat(uint64_t last_seq){
    const uint64_t applied_before = handler_.last_applied();
    const HandlerStats before = handler_.stats();
    handler_.on_heartbeat(last_seq);
    cout << "  heartbeat: the exchange is at seq " << last_seq << ": ";
    if (handler_.stats().retransmits > before.retransmits){
      cout << "seqs " << applied_before + 1 << "-" << last_seq << " missing: retransmitted, book now at seq "
           << handler_.last_applied() << "\n";
    } else if (handler_.stats().snapshots > before.snapshots){
      cout << "missing events too old to retransmit: snapshot, book now at seq " << handler_.last_applied() << "\n";
    } else{
      cout << "nothing missing\n";
    }
  }

 private:
  string missing() const{
    return missing_first_ == missing_last_ ? "seq " + to_string(missing_first_)
                                           : "seqs " + to_string(missing_first_) + "-" + to_string(missing_last_);
  }

  FeedHandler& handler_;
  bool quiet_;
  uint64_t missing_first_ = 0;  // the gap being waited on
  uint64_t missing_last_ = 0;
};

// Sends `events` events from the exchange through the link, tracing each arrival.
void send(Exchange& exchange, LossyLink& link, Tracer& tracer, uint64_t events){
  for (uint64_t sent = 0; sent < events; ++sent){
    for (const Event& event : link.send(exchange.next())){
      tracer.arrive(event);
    }
  }
  for (const Event& event : link.flush()){
    tracer.arrive(event);
  }
}

// Compares the handler's book with the exchange's and prints the verdict.
bool check(const FeedHandler& handler, const Exchange& exchange){
  const bool same = handler.book() == exchange.book() && handler.last_applied() == exchange.last_seq();
  cout << "  result: book at seq " << handler.last_applied() << ", " << handler.book().orders().size()
       << " orders, best bid " << describe(handler.book().best_bid()) << ", best ask "
       << describe(handler.book().best_ask()) << "\n  " << (same ? "matches" : "DIFFERS FROM")
       << " the exchange's own book\n\n";
  return same;
}

bool burst(){
  cout << "== burst: seqs 11-15 are lost on the link ==\n";
  Exchange exchange(1, 1'000);
  LinkConfig config;
  config.drop_ranges = {{11, 15}};
  LossyLink link(config);
  FeedHandler handler(exchange, reorder_window);
  Tracer tracer(handler, false);
  send(exchange, link, tracer, 22);
  return check(handler, exchange);
}

bool reorder(){
  cout << "== reorder: the link holds some events back and sends some twice ==\n";
  Exchange exchange(2, 1'000);
  LinkConfig config;
  config.reorder = 0.2;
  config.duplicate = 0.15;
  config.seed = 4;
  LossyLink link(config);
  FeedHandler handler(exchange, reorder_window);
  Tracer tracer(handler, false);
  send(exchange, link, tracer, 20);
  cout << "  (" << handler.stats().retransmits << " retransmissions needed)\n";
  return check(handler, exchange);
}

bool tail(){
  cout << "== tail: seqs 18-20, the last before the stream goes quiet, are lost ==\n";
  Exchange exchange(3, 1'000);
  LinkConfig config;
  config.drop_ranges = {{18, 20}};
  LossyLink link(config);
  FeedHandler handler(exchange, reorder_window);
  Tracer tracer(handler, false);
  send(exchange, link, tracer, 20);
  tracer.heartbeat(exchange.last_seq());
  return check(handler, exchange);
}

bool outage(){
  cout << "== outage: seqs 201-600 are lost; the exchange keeps only its last 100 events ==\n";
  Exchange exchange(4, 100);
  LinkConfig config;
  config.drop_ranges = {{201, 600}};
  LossyLink link(config);
  FeedHandler handler(exchange, reorder_window);
  Tracer tracer(handler, true);  // 1,000 events: show only the interesting ones
  send(exchange, link, tracer, 1'000);
  tracer.heartbeat(exchange.last_seq());
  return check(handler, exchange);
}

bool random_damage(uint64_t events, double loss, uint64_t seed){
  cout << "== random: " << events << " events, " << loss * 100 << "% loss, 1% duplicated, 2% reordered, seed "
       << seed << " ==\n";
  Exchange exchange(seed, 10'000);
  LinkConfig config;
  config.loss = loss;
  config.duplicate = 0.01;
  config.reorder = 0.02;
  config.seed = seed;
  LossyLink link(config);
  FeedHandler handler(exchange, reorder_window);
  for (uint64_t sent = 0; sent < events; ++sent){
    for (const Event& event : link.send(exchange.next())){
      handler.on_event(event);
    }
  }
  for (const Event& event : link.flush()){
    handler.on_event(event);
  }
  handler.on_heartbeat(exchange.last_seq());

  const HandlerStats& stats = handler.stats();
  cout << "  " << link.lost() << " events lost on the link; " << stats.gaps << " gaps; " << stats.retransmits
       << " recovered by retransmission, " << stats.snapshots << " by snapshot; " << stats.duplicates
       << " duplicates dropped\n";
  return check(handler, exchange) && stats.rejected == 0;
}

bool lamport(){
  constexpr int64_t handoff_latency_us = 5'000;
  cout << "== lamport: instance 0 hands the stream to instance 1; the handoff message takes 5 ms ==\n"
       << "  instance 0's clock runs ahead by    updates   misordered by physical time   by Lamport time\n";
  bool lamport_never_misorders = true;
  for (const int64_t skew_ms : {0, 2, 4, 5, 6, 8, 10, 20}){
    const LamportDemoResult result = run_lamport_demo(skew_ms * 1'000, handoff_latency_us);
    cout << setw(34) << skew_ms << " ms" << setw(10) << result.updates << setw(31) << result.physical_violations
         << setw(18) << result.lamport_violations << "\n";
    lamport_never_misorders = lamport_never_misorders && result.lamport_violations == 0;
  }
  cout << "  Physical time misorders once the skew passes the 5 ms handoff; Lamport time never does.\n\n";
  return lamport_never_misorders;
}

}  // namespace

int main(int argc, char** argv){
  const string scenario = argc > 1 ? argv[1] : "all";
  if (scenario == "burst") return burst() ? EXIT_SUCCESS : EXIT_FAILURE;
  if (scenario == "reorder") return reorder() ? EXIT_SUCCESS : EXIT_FAILURE;
  if (scenario == "tail") return tail() ? EXIT_SUCCESS : EXIT_FAILURE;
  if (scenario == "outage") return outage() ? EXIT_SUCCESS : EXIT_FAILURE;
  if (scenario == "lamport") return lamport() ? EXIT_SUCCESS : EXIT_FAILURE;
  if (scenario == "random"){
    const uint64_t events = argc > 2 ? stoull(argv[2]) : 100'000;
    const double loss = argc > 3 ? stod(argv[3]) / 100.0 : 0.05;
    const uint64_t seed = argc > 4 ? stoull(argv[4]) : 1;
    return random_damage(events, loss, seed) ? EXIT_SUCCESS : EXIT_FAILURE;
  }
  if (scenario == "all"){
    // Every scenario runs, even after a failure, so the output is complete.
    bool ok = burst();
    ok = reorder() && ok;
    ok = tail() && ok;
    ok = outage() && ok;
    ok = random_damage(100'000, 0.05, 1) && ok;
    ok = lamport() && ok;
    return ok ? EXIT_SUCCESS : EXIT_FAILURE;
  }
  cerr << "usage: gapless_demo [burst | reorder | tail | outage | random [events] [loss %] [seed] | lamport | all]\n";
  return 2;
}
