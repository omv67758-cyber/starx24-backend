package com.arenax.tournament.model;

public class Transaction {

    public enum Type { DEPOSIT, WITHDRAWAL, CONTEST_ENTRY, WINNING_REWARD, REFUND }

    public enum Status { SUCCESS, PENDING, FAILED }

    private String id;
    private Type type;
    private String title;
    private long amount;
    private long timestamp;
    private Status status;

    public Transaction() {
        // Required for Firebase deserialization
    }

    public Transaction(String id, Type type, String title, long amount, long timestamp, Status status) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.amount = amount;
        this.timestamp = timestamp;
        this.status = status;
    }

    public String getId() { return id; }
    public Type getType() { return type; }
    public String getTitle() { return title; }
    public long getAmount() { return amount; }
    public long getTimestamp() { return timestamp; }
    public Status getStatus() { return status; }

    /** Deposits, winnings and refunds add money to the wallet; withdrawals and contest entries take it out. */
    public boolean isCredit() {
        return type == Type.DEPOSIT || type == Type.WINNING_REWARD || type == Type.REFUND;
    }
}
