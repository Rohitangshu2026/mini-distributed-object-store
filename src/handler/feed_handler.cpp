#include "handler/feed_handler.hpp"

using namespace std;

void FeedHandler::on_event(const Event& event){
  // Already applied, or already waiting in the buffer: a duplicate.
  if (event.seq < next_seq_ || buffered_.count(event.seq) > 0){
    ++stats_.duplicates;
    return;
  }

  buffered_[event.seq] = event;
  apply_buffered();

  if (buffered_.empty()){  // in sequence, or a late event just closed the gap
    arrivals_during_gap_ = 0;
    return;
  }
  // A gap: something before the first buffered event is missing. It is
  // counted when the wait for it starts.
  if (arrivals_during_gap_ == 0){
    ++stats_.gaps;
  }
  // Give reordered events `reorder_window` more arrivals to turn up; after
  // that, recover everything missing before the first buffered event.
  if (++arrivals_during_gap_ > reorder_window_){
    recover(buffered_.begin()->first - 1);
  }
}

void FeedHandler::on_heartbeat(uint64_t last_seq){
  if (last_seq < next_seq_){
    return;  // nothing is missing
  }
  if (arrivals_during_gap_ == 0){
    ++stats_.gaps;  // not counted yet: e.g. lost at the end of the stream, which no later event revealed
  }
  // Retransmitting events that are already buffered is harmless: the
  // buffered copies are kept and the extra ones ignored.
  recover(last_seq);
}

void FeedHandler::resync(){
  ++stats_.snapshots;
  const Snapshot snapshot = exchange_.snapshot();
  book_ = snapshot.book;
  next_seq_ = snapshot.seq + 1;
  // Buffered events the snapshot already includes are dropped.
  buffered_.erase(buffered_.begin(), buffered_.upper_bound(snapshot.seq));
  arrivals_during_gap_ = 0;
  apply_buffered();
}

void FeedHandler::apply_buffered(){
  for (auto next = buffered_.find(next_seq_); next != buffered_.end(); next = buffered_.find(next_seq_)){
    if (!book_.apply(next->second)){
      ++stats_.rejected;
    }
    buffered_.erase(next);
    ++next_seq_;
  }
}

void FeedHandler::recover(uint64_t last_missing){
  arrivals_during_gap_ = 0;
  const optional<vector<Event>> missing = exchange_.retransmit(next_seq_, last_missing);
  if (!missing){
    resync();  // the range is older than the exchange's history
    return;
  }
  ++stats_.retransmits;
  for (const Event& event : *missing){
    buffered_.emplace(event.seq, event);  // keeps a copy that is already buffered
  }
  apply_buffered();
}
