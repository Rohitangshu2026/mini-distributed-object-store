// The feed handler: consumes the sequenced stream, rebuilds the order book,
// and detects and recovers from gaps.

#pragma once

#include <cstddef>
#include <cstdint>
#include <map>

#include "exchange/exchange.hpp"
#include "book/order_book.hpp"

using namespace std;

struct HandlerStats{
  uint64_t duplicates = 0;   // events already applied or already buffered
  uint64_t gaps = 0;         // gaps seen, including ones a late event filled
  uint64_t retransmits = 0;  // recoveries by retransmission
  uint64_t snapshots = 0;    // recoveries by snapshot
  uint64_t rejected = 0;     // events the book refused; stays zero unless something is broken
};

// How it works:
//
//   - Events are applied strictly in sequence order, so the book is always
//     the exchange's book as of some seq, never a mix.
//   - An event that arrives ahead of the next expected seq means a gap: it
//     waits in a buffer.
//   - A gap is usually a reordered event that arrives a moment later, so the
//     handler waits for `reorder_window` more arrivals. If the gap is still
//     open by then, the missing events are taken as lost and recovered.
//   - Recovery asks the exchange to retransmit the missing range. If the
//     exchange no longer keeps that range, the handler takes a snapshot of
//     the whole book instead and continues from there.
//   - Loss of the last events before the stream goes quiet shows up only
//     when the exchange's heartbeat reports a higher last seq.
class FeedHandler{
 public:
  FeedHandler(const Exchange& exchange, size_t reorder_window)
      : exchange_(exchange), reorder_window_(reorder_window){}

  // One event as it arrives from the network: possibly late, early or twice.
  void on_event(const Event& event);

  // The exchange's latest seq, sent while the stream is idle.
  void on_heartbeat(uint64_t last_seq);

  // Replaces the book with a snapshot and continues after its seq: how a
  // handler joins a stream mid-way, and the fallback when retransmission fails.
  void resync();

  const OrderBook& book() const{ return book_; }
  uint64_t last_applied() const{ return next_seq_ - 1; }
  size_t buffered() const{ return buffered_.size(); }  // events waiting for a gap to close
  const HandlerStats& stats() const{ return stats_; }

 private:
  // Applies buffered events for as long as the next one in sequence is there.
  void apply_buffered();

  // Fills the missing range [next_seq_, last_missing].
  void recover(uint64_t last_missing);

  const Exchange& exchange_;
  size_t reorder_window_;
  OrderBook book_;
  uint64_t next_seq_ = 1;        // the seq to apply next
  map<uint64_t, Event> buffered_;  // events that arrived ahead of next_seq_, by seq
  size_t arrivals_during_gap_ = 0;
  HandlerStats stats_;
};
