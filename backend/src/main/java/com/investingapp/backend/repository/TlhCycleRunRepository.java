package com.investingapp.backend.repository;

import com.investingapp.backend.model.TlhCycleRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TlhCycleRunRepository extends JpaRepository<TlhCycleRun, Long> {
}
