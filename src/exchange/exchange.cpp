#include "exchange/exchange.hpp"

#include <stdexcept>

using namespace std;

namespace{

constexpr int64_t mid_price = 10'000;
// Bids rest up to `band` ticks below the mid and asks up to `band` above it,
// so the book never crosses and the exchange never has to match orders.
constexpr int64_t band = 50;
constexpr size_t max_resting_orders = 200;

}  // namespace

Event Exchange::next(){
  Event event = generate();
  if (!book_.apply(event)){
    throw logic_error("the exchange generated an event that does not fit its own book");
  }
  event.seq = ++last_seq_;
  history_.push_back(event);
  if (history_.size() > history_limit_){
    history_.pop_front();
  }
  return event;
}

// Picks a random action that is valid for the current book: an add 40% of the
// time (while the book is not full), otherwise a modify, remove or execute of
// a random resting order.
Event Exchange::generate(){
  Event event;
  if (resting_.empty() || (resting_.size() < max_resting_orders && random_below(100) < 40)){
    event.type = EventType::add;
    event.order_id = next_order_id_++;
    event.side = random_below(2) == 0 ? Side::bid : Side::ask;
    const int64_t offset = static_cast<int64_t>(1 + random_below(band));
    event.price = event.side == Side::bid ? mid_price - offset : mid_price + offset;
    event.quantity = 1 + random_below(100);
    resting_.push_back(event.order_id);
    return event;
  }

  const size_t index = random_below(resting_.size());
  event.order_id = resting_[index];
  const uint64_t resting_quantity = book_.orders().at(event.order_id).quantity;
  // Drops the order from resting_ when this event takes it out of the book.
  const auto order_leaves = [&]{
    resting_[index] = resting_.back();
    resting_.pop_back();
  };

  switch (random_below(3)){
    case 0:
      event.type = EventType::modify;
      event.quantity = 1 + random_below(100);
      break;
    case 1:
      event.type = EventType::remove;
      order_leaves();
      break;
    default:
      event.type = EventType::execute;
      event.quantity = 1 + random_below(resting_quantity);
      if (event.quantity == resting_quantity){
        order_leaves();
      }
      break;
  }
  return event;
}

optional<vector<Event>> Exchange::retransmit(uint64_t first, uint64_t last) const{
  if (history_.empty() || first > last || first < history_.front().seq || last > last_seq_){
    return nullopt;
  }
  // The history holds consecutive seqs, so an event's position follows from its seq.
  const auto begin = history_.begin() + static_cast<ptrdiff_t>(first - history_.front().seq);
  return vector<Event>(begin, begin + static_cast<ptrdiff_t>(last - first + 1));
}
