package com.protify.portfolio.api.mapper;

import com.protify.portfolio.api.dto.CreateTransactionRequest;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.TransactionResponse;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.MoneyUtils;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Explicit, hand-written mapping — no MapStruct, no reflection. Pure: every number it needs
 * (the instrument, the base currency, the trade-date FX rate) is passed in, so it performs no
 * lookup of its own and is unit-testable without a single mock.
 */
@Component
public class TransactionMapper {

    private final InstrumentMapper instrumentMapper;

    public TransactionMapper(InstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    public RecordTransactionCommand toCommand(CreateTransactionRequest request) {
        return new RecordTransactionCommand(
                normaliseSymbol(request.symbol()),
                request.type(),
                request.quantity() == null ? BigDecimal.ZERO : request.quantity(),
                request.price(),
                request.fees(),
                request.currency(),
                request.executedAt(),
                request.note());
    }

    /**
     * @param instrument {@code null} for a {@code DEPOSIT}/{@code WITHDRAWAL}, which reference no
     *                   instrument
     * @param fxRate     native → base on the transaction's own execution date, {@code 1} when
     *                   those currencies are the same, or {@code null} when no rate for that date
     *                   could be resolved
     */
    public TransactionResponse toResponse(Txn txn, Instrument instrument, CurrencyCode baseCurrency,
            BigDecimal fxRate, List<String> warnings) {
        BigDecimal totalNative = totalNative(txn);
        boolean nativeIsBase = txn.currency() == baseCurrency;

        return new TransactionResponse(
                txn.id(),
                txn.txnType(),
                instrument == null ? null : instrumentMapper.toResponse(instrument),
                MoneyUtils.quantity(txn.quantity()).toPlainString(),
                MoneyDto.of(txn.price(), txn.currency()),
                MoneyDto.of(txn.fees(), txn.currency()),
                MoneyDto.of(totalNative, txn.currency()),
                fxRate == null ? null : MoneyDto.of(totalNative.multiply(fxRate), baseCurrency),
                (fxRate == null || nativeIsBase) ? null : MoneyUtils.fxRate(fxRate).toPlainString(),
                txn.executedAt(),
                txn.note(),
                warnings);
    }

    /**
     * API_CONTRACT.md §7: {@code quantity × price + fees} for a BUY, {@code − fees} for a SELL.
     * The four remaining types carry their whole value in {@code price} (quantity is zero for the
     * three cash types, and a DIVIDEND's {@code price} is the total payout, not a per-share
     * figure) — the same magnitudes {@code ProjectionEngine} folds into the cash balance, so the
     * number shown on a transaction and the number it moved the balance by cannot disagree.
     */
    private static BigDecimal totalNative(Txn txn) {
        return switch (txn.txnType()) {
            case BUY -> txn.quantity().multiply(txn.price()).add(txn.fees());
            case SELL -> txn.quantity().multiply(txn.price()).subtract(txn.fees());
            case DIVIDEND, DEPOSIT, WITHDRAWAL, FEE -> txn.price();
        };
    }

    /** Blank is the same as absent — a form that submits an empty symbol field for a DEPOSIT
     * must not be told it supplied one. */
    private static String normaliseSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        return symbol.strip();
    }
}
