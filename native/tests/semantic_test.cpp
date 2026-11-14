#include "../semantic_index.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <random>

int main() {
    SemanticIndex index;
    std::vector<float> a(256), b(256), invalid(256);
    a[0] = 1; b[1] = 1;
    index.put(1, a); index.put(2, b);
    assert(index.search(a).at(0) == 1);
    index.put(1, b);
    assert(index.search(a).empty());
    index.erase(1); index.erase(999);
    assert(index.search(b).at(0) == 2);
    bool rejected = false;
    try { index.put(3, invalid); } catch (const std::exception&) { rejected = true; }
    assert(rejected);
    std::mt19937 random(1);
    std::uniform_real_distribution<float> distribution(-1, 1);
    for (int i = 3; i <= 10000; ++i) {
        for (float& value : a) value = distribution(random);
        index.put(i, a);
    }
    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 100; ++i) assert(index.search(a).at(0) == 10000);
    auto milliseconds = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::printf("PASS: USearch 10000 vectors, updates/removals/validation; 100 warm queries %.2f ms (host only)\n", milliseconds);
}
