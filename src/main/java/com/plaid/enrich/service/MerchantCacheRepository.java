package com.plaid.enrich.service;

import com.plaid.enrich.domain.MerchantCacheEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for MerchantCacheEntity.
 * Provides lookup by the (description, merchantName) cache key.
 */
@Repository
public interface MerchantCacheRepository extends JpaRepository<MerchantCacheEntity, String> {

    /**
     * Looks up a cached merchant entry by description and merchant name.
     *
     * @param description  the transaction description
     * @param merchantName the merchant name (null coerced to "" before calling)
     * @return the cached entry if present
     */
    Optional<MerchantCacheEntity> findByDescriptionAndMerchantName(String description, String merchantName);
}
