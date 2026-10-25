// The exchange: the source of the sequenced event stream, and the service a
// feed handler recovers missed events from.

#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <optional>
#include <random>
#include <vector>

#include "book/order_book.hpp"

using namespace std;

// The exchange's whole book as it stood after event `seq`.
struct Snapshot{
  uint64_t seq;
  OrderBook book;
};

// Generates a valid event stream from a seed and numbers it 1, 2, 3, ...
//
// Every event is applied to the exchange's own book, so that book is the
// truth a feed handler's book is checked against. The last `history` events
// are kept for retransmission; anything older can only be recovered with a
// snapshot. In a real system retransmit() and snapshot() would be requests
// over TCP to the exchange's recovery service; here they are function calls.
class Exchange{
 public:
  Exchange(uint64_t seed, size_t history) : random_(seed), history_limit_(history){}

  // Generates the next event, applies it to the exchange's book, numbers it
  // and keeps it for retransmission.
  Event next();

  uint64_t last_seq() const{ return last_seq_; }
  const OrderBook& book() const{ return book_; }

  // Events first to last, or nullopt if the range is not all in the history.
  optional<vector<Event>> retransmit(uint64_t first, uint64_t last) const;

  Snapshot snapshot() const{ return {last_seq_, book_}; }

 private:
  Event generate();
  uint64_t random_below(uint64_t bound){ return random_() % bound; }

  mt19937_64 random_;
  OrderBook book_;
  vector<uint64_t> resting_;  // ids of the resting orders, to pick one at random
  uint64_t next_order_id_ = 1;
  uint64_t last_seq_ = 0;
  size_t history_limit_;
  deque<Event> history_;  // the last history_limit_ events, oldest first
};
