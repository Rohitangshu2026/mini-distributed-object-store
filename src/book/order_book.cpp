#include "book/order_book.hpp"

using namespace std;

bool OrderBook::apply(const Event& event){
  if (event.type == EventType::add){
    if (event.quantity == 0 || orders_.count(event.order_id) > 0){
      return false;
    }
    orders_[event.order_id] = Order{event.side, event.price, event.quantity};
    levels(event.side)[event.price] += event.quantity;
    return true;
  }

  // Every other event acts on an order that is already resting.
  const auto order = orders_.find(event.order_id);
  if (order == orders_.end()){
    return false;
  }
  switch (event.type){
    case EventType::modify:
      if (event.quantity == 0){
        return false;
      }
      set_quantity(order, event.quantity);
      return true;
    case EventType::remove:
      set_quantity(order, 0);
      return true;
    case EventType::execute:
      if (event.quantity == 0 || event.quantity > order->second.quantity){
        return false;
      }
      set_quantity(order, order->second.quantity - event.quantity);
      return true;
    case EventType::add:
      break;  // handled above
  }
  return false;
}

void OrderBook::set_quantity(map<uint64_t, Order>::iterator order, uint64_t quantity){
  map<int64_t, uint64_t>& side = levels(order->second.side);
  const auto level = side.find(order->second.price);
  level->second = level->second - order->second.quantity + quantity;
  if (level->second == 0){
    side.erase(level);
  }
  if (quantity == 0){
    orders_.erase(order);
  } else{
    order->second.quantity = quantity;
  }
}

optional<Level> OrderBook::best_bid() const{
  if (bids_.empty()){
    return nullopt;
  }
  return Level{bids_.rbegin()->first, bids_.rbegin()->second};  // the highest bid
}

optional<Level> OrderBook::best_ask() const{
  if (asks_.empty()){
    return nullopt;
  }
  return Level{asks_.begin()->first, asks_.begin()->second};  // the lowest ask
}
