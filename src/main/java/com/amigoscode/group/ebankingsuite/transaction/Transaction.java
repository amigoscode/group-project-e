package com.amigoscode.group.ebankingsuite.transaction;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NonNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "transactions")
@Data
public class Transaction {

    @Id
    @SequenceGenerator(
            name = "transaction_id_sequence",
            sequenceName = "transaction_id_sequence"
    )
    @GeneratedValue(
            strategy = GenerationType.SEQUENCE,
            generator = "transaction_id_sequence"
    )
    private Integer id;
    @NonNull
    private String senderAccountNumber;
    @NonNull
    private String receiverAccountNumber;

    @NonNull
    private String senderName;
    @NonNull
    private String receiverName;
    @NonNull
    private BigDecimal amount;
    @NonNull
    private String referenceNum;
    private String description;
    @Enumerated(EnumType.STRING)
    @NonNull
    private TransactionStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public Transaction(String senderAccountNumber, String receiverAccountNumber, BigDecimal amount, String referenceNum, String description, TransactionStatus status, String senderName, String receiverName) {
        this.senderAccountNumber = senderAccountNumber;
        this.receiverAccountNumber = receiverAccountNumber;
        this.amount = amount;
        this.referenceNum = referenceNum;
        this.description = description;
        this.status = status;
        this.createdAt = LocalDateTime.parse(
                DATE_TIME_FORMATTER.format(LocalDateTime.now()),
                DATE_TIME_FORMATTER);
        this.updatedAt = createdAt;
        this.senderName = senderName;
        this.receiverName = receiverName;
    }

    public Transaction(){
        this.createdAt = LocalDateTime.parse(
                DATE_TIME_FORMATTER.format(LocalDateTime.now()),
                DATE_TIME_FORMATTER);
        this.updatedAt = createdAt;
    }
}
