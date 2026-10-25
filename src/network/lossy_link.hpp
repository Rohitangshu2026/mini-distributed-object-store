// The network between the exchange and the feed handler.

#pragma once

#include <cstdint>
#include <optional>
#include <random>
#include <utility>
#include <vector>

#include "book/order_book.hpp"

using namespace std;

struct LinkConfig{
  double loss = 0.0;       // chance an event is lost
  double duplicate = 0.0;  // chance an event arrives twice
  double reorder = 0.0;    // chance an event is held back and arrives after the next one
  // Injected gaps: every event with a seq in one of these [first, last]
  // ranges is lost.
  vector<pair<uint64_t, uint64_t>> drop_ranges;
  uint64_t seed = 1;
};

// Behaves like UDP multicast: events can be lost, duplicated or reordered,
// at random and in scripted gaps. It never changes an event's contents.
class LossyLink{
 public:
  explicit LossyLink(const LinkConfig& config) : config_(config), random_(config.seed){}

  // Sends one event. Returns the events that arrive at the handler now, in
  // arrival order: none, one, or two.
  vector<Event> send(const Event& event);

  // Delivers the event still held back, if any, when the stream ends.
  vector<Event> flush();

  uint64_t lost() const{ return lost_; }

 private:
  bool chance(double probability){ return bernoulli_distribution(probability)(random_); }

  LinkConfig config_;
  mt19937_64 random_;
  optional<Event> held_;  // a reordered event, delivered after the next one
  uint64_t lost_ = 0;
};
