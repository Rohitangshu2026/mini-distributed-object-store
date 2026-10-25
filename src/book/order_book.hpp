// The market data events and the order book they build.

#pragma once

#include <cstdint>
#include <map>
#include <optional>

using namespace std;

enum class Side{ bid, ask };

enum class EventType{
  add,      // a new order rests in the book
  modify,   // a resting order's quantity changes; its price stays
  remove,   // a resting order is cancelled
  execute,  // part or all of a resting order is filled
};

// One market data event. The exchange numbers events 1, 2, 3, ... in the
// order it publishes them; `seq` is how the feed handler spots gaps.
struct Event{
  uint64_t seq = 0;
  EventType type = EventType::add;
  uint64_t order_id = 0;
  Side side = Side::bid;  // add only
  int64_t price = 0;      // add only, in integer ticks
  uint64_t quantity = 0;  // add: size; modify: the new size; execute: the size filled
};

struct Order{
  Side side;
  int64_t price;
  uint64_t quantity;
  bool operator==(const Order&) const = default;
};

// One price level: a price and the total quantity resting there.
struct Level{
  int64_t price;
  uint64_t quantity;
  bool operator==(const Level&) const = default;
};

// An order book rebuilt from events: every resting order by id, plus the
// total quantity at each price on each side, so the best bid and best ask
// are a map lookup away.
class OrderBook{
 public:
  // Applies one event. Returns false, and changes nothing, if the event does
  // not fit the book: an unknown order, a duplicate id, a zero quantity, or a
  // fill larger than the order.
  bool apply(const Event& event);

  optional<Level> best_bid() const;
  optional<Level> best_ask() const;
  const map<uint64_t, Order>& orders() const{ 
    return orders_;
  }

  // Two books are equal when they hold the same orders; the price levels
  // follow from the orders.
  bool operator==(const OrderBook& other) const{ 
    return orders_ == other.orders_; 
  }

 private:
  map<int64_t, uint64_t>& levels(Side side){
    return side == Side::bid ? bids_ : asks_; 
  }

  // Changes a resting order's quantity and its level's total. At zero the
  // order leaves the book, and so does its level if nothing else rests there.
  void set_quantity(map<uint64_t, Order>::iterator order, uint64_t quantity);

  map<uint64_t, Order> orders_;  // by order id
  map<int64_t, uint64_t> bids_;  // price -> total quantity; the best bid is the highest price
  map<int64_t, uint64_t> asks_;  // price -> total quantity; the best ask is the lowest price
};
