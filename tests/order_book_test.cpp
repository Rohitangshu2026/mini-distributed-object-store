// Tests for the order book: each event type, and events that do not fit.

#include <cstdint>

#include <gtest/gtest.h>

#include "book/order_book.hpp"

using namespace std;

namespace{

Event add(uint64_t order_id, Side side, int64_t price, uint64_t quantity){
  return {0, EventType::add, order_id, side, price, quantity};
}
Event modify(uint64_t order_id, uint64_t quantity){ return {0, EventType::modify, order_id, Side::bid, 0, quantity}; }
Event remove_order(uint64_t order_id){ return {0, EventType::remove, order_id, Side::bid, 0, 0}; }
Event execute(uint64_t order_id, uint64_t quantity){
  return {0, EventType::execute, order_id, Side::bid, 0, quantity};
}

TEST(OrderBook, AppliesEachEventAndRejectsOnesThatDoNotFit){
  OrderBook book;
  EXPECT_TRUE(book.apply(add(1, Side::bid, 99, 10)));
  EXPECT_TRUE(book.apply(add(2, Side::bid, 99, 5)));
  EXPECT_TRUE(book.apply(add(3, Side::ask, 101, 7)));
  EXPECT_EQ(book.best_bid(), (Level{99, 15}));
  EXPECT_EQ(book.best_ask(), (Level{101, 7}));

  EXPECT_TRUE(book.apply(execute(1, 4)));
  EXPECT_TRUE(book.apply(modify(2, 1)));
  EXPECT_EQ(book.best_bid(), (Level{99, 7}));
  EXPECT_TRUE(book.apply(remove_order(3)));
  EXPECT_FALSE(book.best_ask());

  const OrderBook before = book;
  EXPECT_FALSE(book.apply(add(1, Side::bid, 98, 1)));  // the id is already resting
  EXPECT_FALSE(book.apply(execute(1, 7)));             // more than rests
  EXPECT_FALSE(book.apply(remove_order(42)));          // an unknown order
  EXPECT_TRUE(book == before);

  EXPECT_TRUE(book.apply(execute(1, 6)));  // fills the rest, so the order leaves
  EXPECT_EQ(book.orders().count(1), 0u);
}

}  // namespace
