package com.amigoscode.group.ebankingsuite.account;

import com.amigoscode.group.ebankingsuite.account.request.AccountTransactionPinUpdateModel;
import com.amigoscode.group.ebankingsuite.account.response.AccountOverviewResponse;
import com.amigoscode.group.ebankingsuite.exception.AccountNotClearedException;
import com.amigoscode.group.ebankingsuite.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Random;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    private final ClosedAccountRepository closedAccountRepository;
    private static final BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();


    @Autowired
    public AccountService(AccountRepository accountRepository, ClosedAccountRepository closedAccountRepository) {
        this.accountRepository = accountRepository;
        this.closedAccountRepository = closedAccountRepository;
    }

    @Async
    public void createAccount(Account account) {
        account.setAccountNumber(generateUniqueAccountNumber());
        account.setTierLevel(Tier.LEVEL1);
        account.setAccountStatus(AccountStatus.ACTIVATED);
        accountRepository.save(account);
    }

    /**
     * This method generates random 10 digit values and convert to string
     * for use as account number for accounts
     */
    private String generateUniqueAccountNumber(){
        Random random = new SecureRandom();
        StringBuilder accountNumber = new StringBuilder();
        do {
            for (int i = 0; i <= 9; i++) {
                accountNumber.append(Math.abs(random.nextInt(9)));
            }
        }while (accountRepository.existsByAccountNumber(accountNumber.toString()));

        return accountNumber.toString();
    }

    public Account getAccountByUserId(Integer userId) {
        Optional<Account> account = accountRepository.findAccountByUserId(userId);
        if(account.isEmpty()){
            throw new ResourceNotFoundException("account not found");
        }
        return account.get();
    }

    /**
     *Generates basic account overview (i.e. balance, accountNumber, tierLevel, accountStatus)of the user and receives userId
     */
    public AccountOverviewResponse generateAccountOverviewByUserId(Integer userId){
        Account userAccount = getAccountByUserId(userId);
        return new AccountOverviewResponse(
                userAccount.getAccountBalance(),
                userAccount.getAccountNumber(),
                userAccount.getTierLevel().name(),
                userAccount.getAccountStatus().name()
        );
    }

    public void updateAccount(Account existingAccount) {
        existingAccount.setUpdatedAt(LocalDateTime.now());
        accountRepository.save(existingAccount);
    }

    /**
     * This method closes the account by getting the userId from the JWT and the relieving reason
     * from the request body
     */
    public void closeAccount(Integer userId){
        Account userAccount = getAccountByUserId(userId);
        if(!noPendingOrAvailableFundInTheAccount(userAccount)) {
            throw new AccountNotClearedException("confirm there is no pending or available balance in the account");
        }
        userAccount.setAccountStatus(AccountStatus.CLOSED);
        updateAccount(userAccount);
    }

    private boolean noPendingOrAvailableFundInTheAccount(Account account){
        return account.getAccountBalance().equals(BigDecimal.ZERO);
    }

    /**
     * This confirms the pin is 4-digits, more pin standards can be set here
     */
    private boolean pinConformsToStandard(String transactionPin){
        return transactionPin.length() == 4;
    }

    public void updateAccountTransactionPin(int userId,AccountTransactionPinUpdateModel pinUpdateModel){
        Account userAccount = getAccountByUserId(userId);
        if(!pinConformsToStandard(pinUpdateModel.transactionPin())){
            throw new IllegalArgumentException("Bad transaction pin format");
        }
        userAccount.setTransactionPin(bCryptPasswordEncoder.encode(pinUpdateModel.transactionPin()));
        updateAccount(userAccount);
    }

}