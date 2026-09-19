package com.investingapp.backend.repository;

import com.investingapp.backend.model.TlhUserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TlhUserStateRepository extends JpaRepository<TlhUserState, Long> {

    Optional<TlhUserState> findByUserId(Long userId);

    List<TlhUserState> findByLedgerStatus(TlhUserState.LedgerStatus ledgerStatus);
}
