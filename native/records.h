#pragma once
#include <string>
#include <vector>

namespace cw {
struct RecentRecord {
    long long time;
    std::string data;
};

inline void trimRecords(std::vector<RecentRecord> &records, long long now) {
    std::erase_if(records, [now](const RecentRecord &record) { return now - record.time >= 12 * 60 * 60 * 1000; });
    size_t first = records.size(), bytes = 0;
    while (first > 0 && records.size() - first < 99 && bytes + records[first - 1].data.size() <= 256 * 1024) {
        bytes += records[--first].data.size();
    }
    records.erase(records.begin(), records.begin() + first);
}
} // namespace cw
