package com.example.candles.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

import com.example.candles.entity.TelegramBroadcast;

public interface TelegramBroadcastRepository extends JpaRepository<TelegramBroadcast, Long> {

    boolean existsByChatIdAndKindAndDay(long chatId, TelegramBroadcast.Kind kind, LocalDate day);

    List<TelegramBroadcast> findByDayOrderBySentAt(LocalDate day);
}
