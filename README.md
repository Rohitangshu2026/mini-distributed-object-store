# gapless: a market data feed handler

Programming Assignment 1, Distributed Computing, IIIT Bangalore.

The assignment asks for a feed handler that:

1. **consumes a sequenced event stream**: the exchange numbers every event 1, 2, 3, ...;
2. **reconstructs the order book** from those events;
3. **detects and recovers from artificially injected gaps**: events lost, duplicated or reordered on the way;
4. and encourages **Lamport timestamps** once there are several independently clocked handler instances.

This project does all four in one small C++20 program (about 1,000 lines with tests), entirely in one process.
The "network" is a simulated lossy link, and recovery requests are function calls to the exchange.

## Build, test, run

Needs CMake 3.25+ and a C++20 compiler. GoogleTest is downloaded at configure time.

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build
ctest --test-dir build                 # 10 tests
./build/gapless_demo                   # every demo scenario; see "How to demo it"
```

## Directory structure

```
src/
  book/       order_book.hpp/.cpp     Event, Order, Level, OrderBook
  exchange/   exchange.hpp/.cpp       Exchange (event generator, truth book, history), Snapshot
  network/    lossy_link.hpp/.cpp     LossyLink, LinkConfig: loss, duplication, reordering, injected gaps
  handler/    feed_handler.hpp/.cpp   FeedHandler: gap detection and recovery, HandlerStats
  lamport/    lamport.hpp/.cpp        LamportClock, HandlerInstance, log merging, the handoff demo
apps/
  demo.cpp                            gapless_demo: the named demo scenarios
tests/
  order_book_test.cpp                 the book, event by event
  feed_handler_test.cpp               every recovery path, and 20 seeds of random damage
  lamport_test.cpp                    the clock rule, and ordering under clock skew
legacy/                               an earlier, much larger design; not built
```

## System architecture

The exchange generates the stream and is also the recovery service. Events
reach the handler through the lossy link; recovery bypasses the link, the way
a real handler would recover over a reliable TCP connection.

```mermaid
flowchart LR
  subgraph EX["Exchange (src/exchange)"]
    GEN["Event generator<br/>numbers events 1, 2, 3, ..."]
    TRUTH[("Exchange book<br/>the truth")]
    HIST[("History<br/>last N events")]
    GEN --> TRUTH
    GEN --> HIST
  end

  subgraph NET["Lossy link (src/network)"]
    LINK["random loss, duplication, reordering<br/>+ injected gap ranges"]
  end

  subgraph FH["Feed handler (src/handler)"]
    CHECK{"seq vs<br/>next expected"}
    BUF[("Reorder buffer")]
    APPLY["apply in<br/>sequence order"]
    BOOK[("Order book<br/>(src/book)")]
    CHECK -- "early: gap" --> BUF
    CHECK -- "next in sequence" --> APPLY
    BUF --> APPLY
    APPLY --> BOOK
  end

  GEN -- "sequenced events" --> LINK
  LINK -- "events: some lost,<br/>late or doubled" --> CHECK
  GEN -. "heartbeat: last seq" .-> CHECK
  BUF -. "gap still open after 3 arrivals:<br/>retransmit(first, last)" .-> HIST
  BUF -. "range older than history:<br/>snapshot()" .-> TRUTH
```

### Lamport extension

Two handler instances, each with its own physical clock and a Lamport clock,
take turns on the same stream. Instance 0 hands over to instance 1 with a
stamped message; each logs its book updates; the logs are merged afterwards.

```mermaid
flowchart LR
  X["Exchange"] -- "events 1 to 100" --> I0["Instance 0<br/>FeedHandler<br/>physical clock: true time + skew<br/>Lamport clock"]
  X -- "snapshot, then events 101 on" --> I1["Instance 1<br/>FeedHandler<br/>physical clock: true time<br/>Lamport clock"]
  I0 -- "handoff message, stamped L" --> I1
  I0 --> LOG0[("Log 0")]
  I1 --> LOG1[("Log 1")]
  LOG0 --> MERGE{"merge the logs"}
  LOG1 --> MERGE
  MERGE --> PHYS["by physical time:<br/>misorders once skew > handoff time"]
  MERGE --> LAMP["by (Lamport time, instance):<br/>never misorders"]
```

## Class diagrams (UML)

### Feed handling

```mermaid
classDiagram
  direction LR

  class EventType {
    <<enumeration>>
    add
    modify
    remove
    execute
  }
  class Side {
    <<enumeration>>
    bid
    ask
  }
  class Event {
    +uint64_t seq
    +EventType type
    +uint64_t order_id
    +Side side
    +int64_t price
    +uint64_t quantity
  }
  class Order {
    +Side side
    +int64_t price
    +uint64_t quantity
  }
  class Level {
    +int64_t price
    +uint64_t quantity
  }
  class OrderBook {
    -orders_ : map of order id to Order
    -bids_ : map of price to quantity
    -asks_ : map of price to quantity
    +apply(Event) bool
    +best_bid() optional~Level~
    +best_ask() optional~Level~
    +orders() map
  }
  class Snapshot {
    +uint64_t seq
    +OrderBook book
  }
  class Exchange {
    -random_ : mt19937_64
    -book_ : OrderBook
    -history_ : deque~Event~
    -resting_ : vector~uint64_t~
    +next() Event
    +last_seq() uint64_t
    +book() OrderBook
    +retransmit(first, last) optional~vector~Event~~
    +snapshot() Snapshot
  }
  class LinkConfig {
    +double loss
    +double duplicate
    +double reorder
    +drop_ranges : vector of seq ranges
    +uint64_t seed
  }
  class LossyLink {
    -held_ : optional~Event~
    +send(Event) vector~Event~
    +flush() vector~Event~
    +lost() uint64_t
  }
  class HandlerStats {
    +uint64_t duplicates
    +uint64_t gaps
    +uint64_t retransmits
    +uint64_t snapshots
    +uint64_t rejected
  }
  class FeedHandler {
    -next_seq_ : uint64_t
    -buffered_ : map of seq to Event
    -arrivals_during_gap_ : size_t
    +on_event(Event)
    +on_heartbeat(last_seq)
    +resync()
    +book() OrderBook
    +last_applied() uint64_t
    +buffered() size_t
    +stats() HandlerStats
    -apply_buffered()
    -recover(last_missing)
  }

  Event --> EventType
  Event --> Side
  Order --> Side
  OrderBook "1" *-- "*" Order : resting orders
  OrderBook ..> Event : applies
  OrderBook ..> Level : reports best bid/ask
  Exchange *-- OrderBook : the truth
  Exchange ..> Snapshot : creates
  Snapshot *-- OrderBook
  LossyLink *-- LinkConfig
  LossyLink ..> Event : delivers
  FeedHandler *-- OrderBook : rebuilt book
  FeedHandler *-- HandlerStats
  FeedHandler --> Exchange : retransmit / snapshot
```

### Lamport extension

```mermaid
classDiagram
  direction LR

  class LamportClock {
    -time_ : uint64_t
    +tick() uint64_t
    +receive(stamp) uint64_t
  }
  class LogEntry {
    +int instance
    +uint64_t seq
    +uint64_t lamport
    +int64_t physical_us
  }
  class HandlerInstance {
    -id_ : int
    -clock_offset_us_ : int64_t
    +deliver(Event, now_us)
    +hand_off() uint64_t
    +take_over(handoff_stamp, now_us)
    +log() vector~LogEntry~
  }
  class LamportDemoResult {
    +size_t updates
    +size_t physical_violations
    +size_t lamport_violations
  }
  class FeedHandler

  HandlerInstance *-- FeedHandler
  HandlerInstance *-- LamportClock
  HandlerInstance "1" *-- "*" LogEntry : log
  LamportDemoResult ..> LogEntry : counted from merged logs
```

Free functions in `lamport/`: `merge_by_physical_time`, `merge_by_lamport_time`,
`count_order_violations` and `run_lamport_demo`.

## Sequence diagrams

### 1. A lost burst, recovered by retransmission (`gapless_demo burst`)

```mermaid
sequenceDiagram
  participant E as Exchange
  participant L as LossyLink
  participant H as FeedHandler
  participant B as OrderBook

  E->>L: seq 10
  L->>H: seq 10
  H->>B: apply 10
  E->>L: seqs 11 to 15
  Note over L: lost: an injected gap
  E->>L: seq 16
  L->>H: seq 16
  Note over H: expected 11, got 16: buffer it, gap 11-15 opens
  L->>H: seqs 17, 18
  Note over H: buffered: maybe 11-15 are only late
  L->>H: seq 19
  Note over H: gap still open after 3 more arrivals: treat as lost
  H->>E: retransmit(11, 15)
  E-->>H: seqs 11 to 15
  H->>B: apply 11, 12, ..., 19 in order
```

### 2. A reordered event and a duplicate, absorbed (`gapless_demo reorder`)

```mermaid
sequenceDiagram
  participant L as LossyLink
  participant H as FeedHandler
  participant B as OrderBook

  L->>H: seq 2
  H->>B: apply 2
  L->>H: seq 2 again
  Note over H: seq < next expected: duplicate, dropped
  L->>H: seq 4 (3 was held back)
  Note over H: gap: buffer 4, wait
  L->>H: seq 3, late
  H->>B: apply 3, then buffered 4
  Note over H: gap closed within the window: no retransmission
```

### 3. A gap older than the exchange's history: snapshot (`gapless_demo outage`)

```mermaid
sequenceDiagram
  participant E as Exchange
  participant H as FeedHandler
  participant B as OrderBook

  Note over H: seqs 201-600 lost, 601-604 buffered
  H->>E: retransmit(201, 600)
  E-->>H: nothing: only the last 100 events are kept
  H->>E: snapshot()
  E-->>H: whole book as of seq 604
  H->>B: replace the book
  Note over H: drop buffered seqs up to 604, continue from 605
```

### 4. Loss at the end of the stream: the heartbeat (`gapless_demo tail`)

```mermaid
sequenceDiagram
  participant E as Exchange
  participant L as LossyLink
  participant H as FeedHandler

  E->>L: seq 17
  L->>H: seq 17
  E->>L: seqs 18 to 20
  Note over L: lost, and no later event arrives to reveal it
  E->>H: heartbeat: last seq is 20
  Note over H: applied only up to 17
  H->>E: retransmit(18, 20)
  E-->>H: seqs 18 to 20
```

### 5. Lamport timestamps across a handoff (`gapless_demo lamport`)

```mermaid
sequenceDiagram
  participant X as Exchange
  participant A as Instance 0 (clock runs ahead)
  participant C as Instance 1

  X->>A: events 1 to 100
  Note over A: each update: L = L + 1, logged with (L, own clock)
  A->>C: handoff message, stamped L0
  Note over C: L = max(L, L0) + 1, so L > L0
  C->>X: snapshot()
  X-->>C: book as of seq S
  X->>C: events S+1 onwards
  Note over A,C: every update of instance 1 has L > L0 >= every L of instance 0
```

## How it works

- **Sequence numbers are the only clock that matters within a stream.** The handler applies events strictly in `seq` order, so its book is always the exchange's book as of some seq, never a mix.
- **Duplicates**: an event whose seq was already applied, or is already buffered, is dropped.
- **Gap detection**: an event beyond the next expected seq means something before it is missing. It is buffered.
- **Reordering vs loss**: a missing event is usually just late, so the handler waits 3 more arrivals (the reorder window). If the gap is still open, it is loss.
- **Recovery**: ask the exchange to retransmit the missing range. If the exchange no longer keeps it, take a snapshot of the whole book and continue after the snapshot's seq.
- **Tail loss**: when the stream goes quiet, the exchange's heartbeat reports its last seq; anything missing up to it is recovered the same way.
- **Lamport timestamps**: every update and message ticks the instance's Lamport clock, and receiving the handoff sets `L = max(L, L_msg) + 1`. So everything instance 0 did happens-before everything instance 1 does, and sorting by `(L, instance)` keeps the books' seq order whatever the physical clocks say. Sorting by physical time breaks once instance 0's clock leads by more than the handoff time.

## How to demo it

About five minutes. Build first (see above), then:

| Step | Command | What to point out |
| --- | --- | --- |
| 1 | `ctest --test-dir build` | 10 tests pass: the book, each recovery path, 20 random seeds, and the Lamport property |
| 2 | `./build/gapless_demo burst` | Seqs 11-15 are lost. 16 opens the gap, 17 and 18 wait in the buffer, and at 19 the handler gives up waiting and retransmits. The final book matches the exchange's |
| 3 | `./build/gapless_demo reorder` | Late events close gaps on their own and duplicates are dropped: **0 retransmissions**. This is why the handler waits a few arrivals before recovering |
| 4 | `./build/gapless_demo tail` | The last 3 events are lost and nothing after them arrives; only the heartbeat reveals the loss |
| 5 | `./build/gapless_demo outage` | 400 events lost, but the exchange keeps only 100: retransmission is impossible, so the handler takes a snapshot |
| 6 | `./build/gapless_demo random 1000000 20 7` | A million events at 20% loss, under a second: about 160,000 gaps recovered, and the book still matches exactly |
| 7 | `./build/gapless_demo lamport` | The table: physical time misorders updates once the clock skew passes the 5 ms handoff; Lamport time never does |

Questions to expect, and short answers:

- *Why not apply events as they arrive?* The book would be wrong after any reordering; applying strictly in seq order makes it always equal to the exchange's book at some seq.
- *Why wait 3 arrivals before recovering?* A reordered event is not lost. Recovering at once would retransmit every late event (step 3 shows they need none).
- *What if the retransmission itself is lost?* Recovery runs over a reliable channel (TCP in a real system; a function call here), so it is not.
- *Why Lamport and not physical timestamps?* Instances' clocks are not synchronised, and skew larger than the handoff time reorders updates (step 7). Lamport time respects happens-before, which here comes from the handoff message.
- *What does Lamport time not give you?* `L(a) < L(b)` does not mean `a` happened before `b`; it orders consistently with causality, nothing more.
