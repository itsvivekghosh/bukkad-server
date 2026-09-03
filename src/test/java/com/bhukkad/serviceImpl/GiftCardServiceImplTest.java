package com.bhukkad.serviceImpl;

import com.bhukkad.config.GiftCardProperties;
import com.bhukkad.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.dto.request.GiftCardRedeemRequest;
import com.bhukkad.dto.response.GiftCardResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.GiftCard;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.GiftCardRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.util.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GiftCardServiceImplTest {

    @Mock
    private GiftCardRepository giftCardRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private GiftCardProperties giftCardProperties;

    @InjectMocks
    private GiftCardServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(giftCardProperties.getMinAmount()).thenReturn(100.0);
        lenient().when(giftCardProperties.getMaxAmount()).thenReturn(10000.0);
        lenient().when(securityUtils.getCurrentUserId()).thenReturn(1L);
    }

    @Test
    void purchaseGiftCard_belowMinAmount_throws() {
        GiftCardPurchaseRequest request = new GiftCardPurchaseRequest();
        request.setAmount(50.0);
        request.setRecipientEmail("test@example.com");
        request.setRecipientName("Test");
        request.setExpiresAt(LocalDate.now().plusDays(30));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        assertThrows(BusinessException.class, () -> service.purchaseGiftCard(request));
    }

    @Test
    void purchaseGiftCard_success() {
        GiftCardPurchaseRequest request = new GiftCardPurchaseRequest();
        request.setAmount(500.0);
        request.setRecipientEmail("test@example.com");
        request.setRecipientName("Test User");
        request.setMessage("Happy Birthday!");
        request.setExpiresAt(LocalDate.now().plusDays(30));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(giftCardRepository.existsByCode(any())).thenReturn(false);
        when(giftCardRepository.save(any(GiftCard.class))).thenAnswer(inv -> inv.getArgument(0));

        GiftCardResponse response = service.purchaseGiftCard(request);

        assertNotNull(response);
        assertEquals(500.0, response.getAmount());
        assertEquals(500.0, response.getBalance());
        assertEquals(GiftCard.Status.ACTIVE, response.getStatus());
        assertEquals("test@example.com", response.getRecipientEmail());
        verify(giftCardRepository).save(any(GiftCard.class));
    }

    @Test
    void redeemGiftCard_invalidCode_throws() {
        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("INVALID");

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(giftCardRepository.findByCode("INVALID")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.redeemGiftCard(request));
    }

    @Test
    void redeemGiftCard_expired_throws() {
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setStatus(GiftCard.Status.ACTIVE);
        giftCard.setBalance(100.0);
        giftCard.setExpiresAt(LocalDateTime.now().minusDays(1));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("GIFT-1234567");

        assertThrows(BusinessException.class, () -> service.redeemGiftCard(request));
        assertEquals(GiftCard.Status.EXPIRED, giftCard.getStatus());
    }

    @Test
    void redeemGiftCard_success() {
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setStatus(GiftCard.Status.ACTIVE);
        giftCard.setBalance(100.0);
        giftCard.setExpiresAt(LocalDateTime.now().plusDays(30));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));
        when(giftCardRepository.save(any(GiftCard.class))).thenAnswer(inv -> inv.getArgument(0));

        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("GIFT-1234567");

        GiftCardResponse response = service.redeemGiftCard(request);

        assertNotNull(response);
        assertEquals(0.0, response.getBalance());
        assertEquals(GiftCard.Status.REDEEMED, response.getStatus());
        assertNotNull(response.getRedeemedAt());
        verify(giftCardRepository).save(giftCard);
    }

    @Test
    void getGiftCardByCode_notFound_throws() {
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getGiftCardByCode("GIFT-1234567"));
    }

    @Test
    void getMyGiftCards_returnsPurchasedCards() {
        Customer customer = new Customer();
        customer.setId(1L);

        GiftCard card = new GiftCard();
        card.setId(1L);
        card.setCode("GIFT-1234567");

        when(giftCardRepository.findByPurchasedById(1L)).thenReturn(List.of(card));

        var result = service.getMyGiftCards();

        assertEquals(1, result.size());
        assertEquals("GIFT-1234567", result.get(0).getCode());
    }

    // ==================== additional coverage ====================

    @Test
    void purchaseGiftCard_aboveMaxAmount_throws() {
        GiftCardPurchaseRequest request = new GiftCardPurchaseRequest();
        request.setAmount(20000.0);
        request.setRecipientEmail("test@example.com");
        request.setRecipientName("Test");
        request.setExpiresAt(LocalDate.now().plusDays(30));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(new Customer()));

        assertThrows(BusinessException.class, () -> service.purchaseGiftCard(request));
    }

    @Test
    void purchaseGiftCard_customerNotFound_throws() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.purchaseGiftCard(new GiftCardPurchaseRequest()));
    }

    @Test
    void purchaseGiftCard_notificationFailure_stillReturnsResponse() {
        GiftCardPurchaseRequest request = new GiftCardPurchaseRequest();
        request.setAmount(500.0);
        request.setRecipientEmail("test@example.com");
        request.setRecipientName("Test User");
        request.setMessage("Hi");
        request.setExpiresAt(LocalDate.now().plusDays(30));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(new Customer()));
        when(giftCardRepository.existsByCode(any())).thenReturn(false);
        when(giftCardRepository.save(any(GiftCard.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("mail server down"))
                .when(notificationHelper).sendGiftCardNotification(any(), any(), any(), any(), any());

        GiftCardResponse response = service.purchaseGiftCard(request);

        assertNotNull(response);
        assertEquals(GiftCard.Status.ACTIVE, response.getStatus());
    }

    @Test
    void redeemGiftCard_notActive_throws() {
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setStatus(GiftCard.Status.REDEEMED);
        giftCard.setBalance(100.0);
        giftCard.setExpiresAt(LocalDateTime.now().plusDays(30));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(new Customer()));
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("GIFT-1234567");

        assertThrows(BusinessException.class, () -> service.redeemGiftCard(request));
    }

    @Test
    void redeemGiftCard_zeroBalance_marksRedeemedAndThrows() {
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setStatus(GiftCard.Status.ACTIVE);
        giftCard.setBalance(0.0);
        giftCard.setExpiresAt(LocalDateTime.now().plusDays(30));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(new Customer()));
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("GIFT-1234567");

        assertThrows(BusinessException.class, () -> service.redeemGiftCard(request));
        assertEquals(GiftCard.Status.REDEEMED, giftCard.getStatus());
        verify(giftCardRepository).save(giftCard);
    }

    @Test
    void redeemGiftCard_lowercaseInput_uppercasedForLookup() {
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setStatus(GiftCard.Status.ACTIVE);
        giftCard.setBalance(100.0);
        giftCard.setExpiresAt(LocalDateTime.now().plusDays(30));

        when(customerRepository.findById(1L)).thenReturn(Optional.of(new Customer()));
        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));
        when(giftCardRepository.save(any(GiftCard.class))).thenAnswer(inv -> inv.getArgument(0));

        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        request.setCode("gift-1234567");

        GiftCardResponse response = service.redeemGiftCard(request);

        assertEquals(0.0, response.getBalance());
    }

    @Test
    void getGiftCardByCode_purchaserAllowed() {
        Customer purchaser = new Customer();
        purchaser.setId(1L);
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setPurchasedBy(purchaser);
        giftCard.setRedeemedBy(null);

        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        GiftCardResponse response = service.getGiftCardByCode("gift-1234567");

        assertNotNull(response);
    }

    @Test
    void getGiftCardByCode_redeemerAllowed() {
        Customer purchaser = new Customer();
        purchaser.setId(2L);
        Customer redeemer = new Customer();
        redeemer.setId(1L);
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setPurchasedBy(purchaser);
        giftCard.setRedeemedBy(redeemer);

        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        GiftCardResponse response = service.getGiftCardByCode("GIFT-1234567");

        assertNotNull(response);
    }

    @Test
    void getGiftCardByCode_unrelatedCustomer_throws() {
        Customer purchaser = new Customer();
        purchaser.setId(2L);
        Customer redeemer = new Customer();
        redeemer.setId(3L);
        GiftCard giftCard = new GiftCard();
        giftCard.setCode("GIFT-1234567");
        giftCard.setPurchasedBy(purchaser);
        giftCard.setRedeemedBy(redeemer);

        when(giftCardRepository.findByCode("GIFT-1234567")).thenReturn(Optional.of(giftCard));

        assertThrows(BusinessException.class, () -> service.getGiftCardByCode("GIFT-1234567"));
    }

    @Test
    void getGiftCardsPurchasedForMe_mapsActiveCards() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setEmail("me@example.com");
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        GiftCard card = new GiftCard();
        card.setId(9L);
        card.setCode("GIFT-7777777");
        when(giftCardRepository.findByRecipientEmailAndStatus("me@example.com", GiftCard.Status.ACTIVE))
                .thenReturn(List.of(card));

        var result = service.getGiftCardsPurchasedForMe();

        assertEquals(1, result.size());
        assertEquals("GIFT-7777777", result.get(0).getCode());
    }

    @Test
    void getGiftCardsPurchasedForMe_customerNotFound_throws() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getGiftCardsPurchasedForMe());
    }
}