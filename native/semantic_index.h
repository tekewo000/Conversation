#pragma once
#include <usearch/index_dense.hpp>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <vector>

class SemanticIndex {
    unum::usearch::index_dense_t index_;
    static void validate(const std::vector<float>& vector) {
        if (vector.size() != 256) throw std::runtime_error("Invalid embedding dimensions");
        double norm = 0;
        for (float value : vector) {
            if (!std::isfinite(value)) throw std::runtime_error("Invalid embedding value");
            norm += double(value) * value;
        }
        if (norm < 1e-12 || norm > 1e6) throw std::runtime_error("Invalid embedding magnitude");
    }
public:
    SemanticIndex() {
        using namespace unum::usearch;
        auto result = index_dense_t::make(metric_punned_t(256, metric_kind_t::cos_k, scalar_kind_t::f16_k), {},
            default_free_value<std::uint64_t>(), index_limits_t(128, 1));
        result.error.raise();
        index_ = std::move(result.index);
    }
    void erase(std::int64_t id) {
        if (id <= 0) throw std::runtime_error("Invalid memory ID");
        auto result = index_.remove(static_cast<std::uint64_t>(id));
        result.error.raise();
    }
    void put(std::int64_t id, const std::vector<float>& vector) {
        validate(vector);
        if (id <= 0) throw std::runtime_error("Invalid memory ID");
        if (index_.size() + 1 > index_.capacity()) index_.reserve({index_.capacity() * 2, 1});
        if (index_.contains(static_cast<std::uint64_t>(id))) erase(id);
        auto result = index_.add(static_cast<std::uint64_t>(id), vector.data(), 0);
        result.error.raise();
    }
    std::vector<std::int64_t> search(const std::vector<float>& vector) {
        validate(vector);
        if (!index_.size()) return {};
        auto result = index_.search(vector.data(), 24, 0);
        result.error.raise();
        std::vector<std::int64_t> ids;
        for (std::size_t i = 0; i < result.size(); ++i) {
            auto match = result[i];
            if (match.distance <= 0.35f) ids.push_back(static_cast<std::int64_t>(match.member.key));
        }
        return ids;
    }
};
