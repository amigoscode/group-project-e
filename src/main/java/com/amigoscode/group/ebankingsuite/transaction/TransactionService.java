package com.amigoscode.group.ebankingsuite.transaction;

import com.amigoscode.group.ebankingsuite.account.Account;
import com.amigoscode.group.ebankingsuite.account.AccountService;
import com.amigoscode.group.ebankingsuite.exception.ResourceNotFoundException;
import com.amigoscode.group.ebankingsuite.exception.ValueMismatchException;
import com.amigoscode.group.ebankingsuite.notification.NotificationSenderService;
import com.amigoscode.group.ebankingsuite.notification.emailNotification.request.FundsAlertNotificationRequest;
import com.amigoscode.group.ebankingsuite.transaction.request.FundsTransferRequest;
import com.amigoscode.group.ebankingsuite.transaction.request.TransactionHistoryRequest;
import com.amigoscode.group.ebankingsuite.transaction.response.TransactionHistoryResponse;
import com.amigoscode.group.ebankingsuite.transaction.response.TransactionType;
import com.amigoscode.group.ebankingsuite.user.UserService;
import com.itextpdf.text.*;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountService accountService;
    private final UserService userService;
    private final NotificationSenderService notificationSenderService;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    @Autowired
    public TransactionService(TransactionRepository transactionRepository, AccountService accountService, UserService userService, NotificationSenderService notificationSenderService) {
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
        this.userService = userService;
        this.notificationSenderService = notificationSenderService;
    }


    @Transactional
    public void transferFunds(FundsTransferRequest request){
        if(!request.senderAccountNumber().equals(request.receiverAccountNumber())){
            Account senderAccount = accountService.accountExistsAndIsActivated(request.senderAccountNumber());
            if(ENCODER.matches(request.transactionPin(), senderAccount.getTransactionPin())) {
                Account receiverAccount = accountService.accountExistsAndIsActivated(request.receiverAccountNumber());
                accountService.debitAccount(senderAccount, request.amount());
                accountService.creditAccount(receiverAccount, request.amount());
                saveNewTransaction(request, senderAccount, receiverAccount);
                notificationSenderService.sendCreditAndDebitNotification(new FundsAlertNotificationRequest(senderAccount.getUserId(),receiverAccount.getUserId(),senderAccount.getAccountBalance(),receiverAccount.getAccountBalance(),request.amount()));
                return;
            }
            throw new ValueMismatchException("incorrect transaction pin");
        }
        throw new IllegalArgumentException("sender account cannot be recipient account");
    }

    /**
     * This method save a new transaction after completion, it is an asynchronous process
     * because the method using it doesn't need it response
     */
    @Async
    public void saveNewTransaction(FundsTransferRequest request, Account senderAccount, Account receiverAccount){

        transactionRepository.save(
                new Transaction(request.senderAccountNumber(),
                        request.receiverAccountNumber(),
                        request.amount(),
                        generateTransactionReference(),
                        request.narration(),
                        TransactionStatus.SUCCESS,
                        userService.getUserByUserId(senderAccount.getUserId()).getFullName(),
                        userService.getUserByUserId(receiverAccount.getUserId()).getFullName())
        );
    }

    /**
     * generates random reference number it keeps generating until it gets a unique value.
     */
    private String generateTransactionReference(){
        final String VALUES = "abcdefghijklmnopqrstuvwxyz0123456789";
        final int referenceNumberLength = 12;
        StringBuilder builder = new StringBuilder(referenceNumberLength);
        do {
            for (int i = 0; i < referenceNumberLength; i++) {
                builder.append(VALUES.charAt(SECURE_RANDOM.nextInt(VALUES.length())));
            }
        }while (transactionRepository.existsByReferenceNum(builder.toString()));
        return builder.toString();
    }

    /**
     * This method returns a list of transactions for a particular account by userId
     */
    public List<TransactionHistoryResponse> getTransactionHistoryByUserId(TransactionHistoryRequest request, int userId, Pageable pageable) {
        Account userAccount = accountService.getAccountByUserId(userId);
        Slice<Transaction> transactions = transactionRepository.findAllByStatusAndCreatedAtBetweenAndSenderAccountNumberOrReceiverAccountNumber(
                TransactionStatus.SUCCESS,
                request.startDateTime(),
                request.endDateTime(),
                userAccount.getAccountNumber(),
                userAccount.getAccountNumber(),
                pageable
        );
        if(transactions.getContent().isEmpty()){
            throw new ResourceNotFoundException("no transactions");
        }

        return formatTransactions(transactions.getContent(), userAccount.getAccountNumber());

    }

    /**
     * This method formats the transactions into the desired format which classifies each transaction into either credit and debit for easier understanding.
     */
    public List<TransactionHistoryResponse> formatTransactions(List <Transaction> transactions, String userAccountNumber){

        List<TransactionHistoryResponse> transactionHistoryResponses = new ArrayList<>();

        transactions.forEach(
                    transaction -> {
                        TransactionHistoryResponse transactionHistoryResponse = new TransactionHistoryResponse();
                        transactionHistoryResponse.setTransactionDateTime(transaction.getCreatedAt());
                        transactionHistoryResponse.setAmount(transaction.getAmount());
                        transactionHistoryResponse.setReceiverName(transaction.getReceiverName());
                        transactionHistoryResponse.setSenderName(transaction.getSenderName());
                        transactionHistoryResponse.setTransactionType(checkTransactionType(transaction, userAccountNumber));
                        transactionHistoryResponses.add(transactionHistoryResponse);
                    }
        );

        return transactionHistoryResponses;
    }

    public TransactionType checkTransactionType(Transaction transaction, String userAccountNumber){

        if(transaction.getReceiverAccountNumber().equals(userAccountNumber)){
            return TransactionType.CREDIT;
        }else if(transaction.getSenderAccountNumber().equals(userAccountNumber)){
            return TransactionType.DEBIT;
        }
           throw new IllegalArgumentException("error processing cannot determine transaction type");
    }

    /**
     * This method generates an account statement for a particular account by userId, month, year and returns it as a pdf file
     */
    public ByteArrayOutputStream generateTransactionStatement(int userId, int year, Integer month, int pageNum, int pageSize) throws DocumentException {
        Account account = accountService.getAccountByUserId(userId);
        LocalDateTime startDate = LocalDateTime.of(year, month == null ? 1 : month, 1, 0, 0);
        LocalDateTime endDate = LocalDateTime.of(year, month == null ? 12 : month, month == null ? 31 : startDate.toLocalDate().lengthOfMonth(), 23, 59);
        Page<Transaction> transactions = transactionRepository.findAllByStatusAndCreatedAtBetweenAndSenderAccountNumberOrReceiverAccountNumber(
                TransactionStatus.SUCCESS,
                startDate,
                endDate,
                account.getAccountNumber(),
                account.getAccountNumber(),
                PageRequest.of(pageNum, pageSize)
        );
        if (transactions.isEmpty()) {
            throw new ResourceNotFoundException("No transactions found for the specified period.");
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Document document = new Document();
        PdfWriter.getInstance(document, outputStream);
        document.open();

        String period;
        if (month == null) {
            period = "" + year;
        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
            String monthName = formatter.format(Month.of(month));
            period = monthName + " " + year;
        }

        document.add(new Paragraph("Account Statement for " + ("") + period));
        document.add(new Paragraph("Account Number: " + account.getAccountNumber()));
        document.add(new Paragraph("Account Holder: " + userService.getUserByUserId(account.getUserId()).getFullName()));
        document.add(Chunk.NEWLINE);

        Font boldFont = new Font(Font.FontFamily.TIMES_ROMAN, 12, Font.BOLD);
        PdfPTable table = new PdfPTable(new float[]{1, 1, 1, 1, 1, 1});
        table.addCell(new PdfPCell(new Phrase("Reference Number", boldFont)));
        table.addCell(new PdfPCell(new Phrase("Transaction Date", boldFont)));
        table.addCell(new PdfPCell(new Phrase("Amount", boldFont)));
        table.addCell(new PdfPCell(new Phrase("Sender", boldFont)));
        table.addCell(new PdfPCell(new Phrase("Recipient", boldFont)));
        table.addCell(new PdfPCell(new Phrase("Description", boldFont)));
        transactions.forEach(transaction -> {
            table.addCell(new PdfPCell(new Phrase(transaction.getReferenceNum())));
            table.addCell(new PdfPCell(new Phrase(transaction.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))));
            table.addCell(new PdfPCell(new Phrase(String.format("%.2f", transaction.getAmount()))));
            table.addCell(new PdfPCell(new Phrase(transaction.getSenderName())));
            table.addCell(new PdfPCell(new Phrase(transaction.getReceiverName())));
            table.addCell(new PdfPCell(new Phrase(transaction.getDescription())));
        });
        document.add(table);

        document.close();
        return outputStream;
    }
}
