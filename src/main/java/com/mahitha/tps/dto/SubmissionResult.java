package com.mahitha.tps.dto;

import com.mahitha.tps.model.TransactionRecord;

public record SubmissionResult(String requestId, String outcome, TransactionRecord transaction, String error) {
}
