// Tests for the feed handler: each recovery path, and random damage across
// many seeds. Every test ends by checking the handler's book against the
// exchange's own book.

#include <cstdint>
#include <string>

#include <gtest/gtest.h>

#include "exchange/exchange.hpp"
#include "handler/feed_handler.hpp"
#include "network/lossy_link.hpp"

using namespace std;

namespace{

// Sends `events` events from the exchange through the link to the handler,
// then, if asked, lets the stream go quiet with a heartbeat.
void stream(Exchange& exchange, LossyLink& link, FeedHandler& handler, uint64_t events, bool heartbeat = true){
  for (uint64_t sent = 0; sent < events; ++sent){
    for (const Event& event : link.send(exchange.next())){
      handler.on_event(event);
    }
  }
  for (const Event& event : link.flush()){
    handler.on_event(event);
  }
  if (heartbeat){
    handler.on_heartbeat(exchange.last_seq());
  }
}

void expect_same_book(const FeedHandler& handler, const Exchange& exchange){
  EXPECT_EQ(handler.last_applied(), exchange.last_seq());
  EXPECT_TRUE(handler.book() == exchange.book());
  EXPECT_EQ(handler.book().best_bid(), exchange.book().best_bid());
  EXPECT_EQ(handler.book().best_ask(), exchange.book().best_ask());
  EXPECT_EQ(handler.stats().rejected, 0u);
}

TEST(FeedHandler, LosslessStreamRebuildsTheBook){
  Exchange exchange(1, 1'000);
  LossyLink link({});
  FeedHandler handler(exchange, 3);
  stream(exchange, link, handler, 5'000);
  expect_same_book(handler, exchange);
  EXPECT_EQ(handler.stats().gaps, 0u);
}

TEST(FeedHandler, InjectedGapIsRecoveredByRetransmission){
  Exchange exchange(2, 1'000);
  LinkConfig config;
  config.drop_ranges = {{101, 150}};
  LossyLink link(config);
  FeedHandler handler(exchange, 3);
  stream(exchange, link, handler, 1'000, false);
  expect_same_book(handler, exchange);
  EXPECT_EQ(handler.stats().gaps, 1u);
  EXPECT_EQ(handler.stats().retransmits, 1u);
  EXPECT_EQ(handler.stats().snapshots, 0u);
}

TEST(FeedHandler, ReorderingAndDuplicatesNeedNoRecovery){
  Exchange exchange(3, 1'000);
  LinkConfig config;
  config.reorder = 0.2;
  config.duplicate = 0.1;
  LossyLink link(config);
  FeedHandler handler(exchange, 3);
  stream(exchange, link, handler, 5'000);
  expect_same_book(handler, exchange);
  EXPECT_GT(handler.stats().gaps, 0u);  // each reordered event opens a gap briefly
  EXPECT_EQ(handler.stats().retransmits, 0u);
  EXPECT_GT(handler.stats().duplicates, 0u);
}

TEST(FeedHandler, HeartbeatExposesLossOfTheLastEvents){
  Exchange exchange(4, 1'000);
  LinkConfig config;
  config.drop_ranges = {{496, 500}};
  LossyLink link(config);
  FeedHandler handler(exchange, 3);
  stream(exchange, link, handler, 500, false);
  EXPECT_EQ(handler.last_applied(), 495u);  // nothing after the loss reveals it

  handler.on_heartbeat(exchange.last_seq());
  expect_same_book(handler, exchange);
  EXPECT_EQ(handler.stats().retransmits, 1u);
}

TEST(FeedHandler, GapOlderThanTheHistoryIsRecoveredBySnapshot){
  Exchange exchange(5, 100);  // keeps only the last 100 events
  LinkConfig config;
  config.drop_ranges = {{201, 600}};
  LossyLink link(config);
  FeedHandler handler(exchange, 3);
  stream(exchange, link, handler, 1'000);
  expect_same_book(handler, exchange);
  EXPECT_EQ(handler.stats().snapshots, 1u);
}

TEST(FeedHandler, LateJoinStartsFromASnapshot){
  Exchange exchange(6, 1'000);
  for (int event = 0; event < 500; ++event){
    exchange.next();
  }
  LossyLink link({});
  FeedHandler handler(exchange, 3);
  handler.resync();
  EXPECT_EQ(handler.last_applied(), 500u);
  stream(exchange, link, handler, 500);
  expect_same_book(handler, exchange);
}

TEST(FeedHandler, RandomDamageAcrossSeedsNeverLeavesAWrongBook){
  for (uint64_t seed = 1; seed <= 20; ++seed){
    Exchange exchange(seed, 2'000);
    LinkConfig config;
    config.loss = 0.10;
    config.duplicate = 0.02;
    config.reorder = 0.05;
    config.seed = seed;
    LossyLink link(config);
    FeedHandler handler(exchange, 3);
    stream(exchange, link, handler, 20'000);
    SCOPED_TRACE("seed " + to_string(seed));
    expect_same_book(handler, exchange);
  }
}

}  // namespace
