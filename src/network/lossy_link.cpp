#include "network/lossy_link.hpp"

using namespace std;

vector<Event> LossyLink::send(const Event& event){
  vector<Event> arrived;

  bool in_drop_range = false;
  for (const auto& [first, last] : config_.drop_ranges){
    in_drop_range = in_drop_range || (first <= event.seq && event.seq <= last);
  }

  if (in_drop_range || chance(config_.loss)){
    ++lost_;
  } else if (!held_ && chance(config_.reorder)){
    held_ = event;  // arrives after the next event
    return arrived;
  } else{
    arrived.push_back(event);
    if (chance(config_.duplicate)){
      arrived.push_back(event);
    }
  }

  // An event held back last time arrives now, after the later one.
  if (held_){
    arrived.push_back(*held_);
    held_.reset();
  }
  return arrived;
}

vector<Event> LossyLink::flush(){
  vector<Event> arrived;
  if (held_){
    arrived.push_back(*held_);
    held_.reset();
  }
  return arrived;
}
