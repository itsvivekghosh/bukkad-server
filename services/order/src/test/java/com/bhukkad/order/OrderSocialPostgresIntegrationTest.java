package com.bhukkad.order;

import com.bhukkad.order.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 4 order depth (group orders, gift cards, subscriptions)
 * against PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderSocialPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private GroupOrderRepository groupRepository;
    @Autowired private GroupOrderMemberRepository memberRepository;
    @Autowired private GiftCardRepository giftCardRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM subscriptions");
        jdbcTemplate.update("DELETE FROM group_order_members");
        jdbcTemplate.update("DELETE FROM group_orders");
        jdbcTemplate.update("DELETE FROM gift_cards");
    }

    @Test
    void migration_appliedV4Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('group_orders','group_order_members','gift_cards','subscriptions')",
                Integer.class);
        assertThat(tables).isEqualTo(4);
    }

    @Test
    void groupOrderAndMembersPersist() {
        GroupOrder group = new GroupOrder();
        group.setHostUserId(1L);
        group.setRestaurantId(2L);
        group.setStatus(GroupOrder.STATUS_OPEN);
        GroupOrder saved = groupRepository.saveAndFlush(group);

        GroupOrderMember member = new GroupOrderMember();
        member.setGroupOrderId(saved.getId());
        member.setCustomerId(3L);
        member.setStatus("MEMBER");
        memberRepository.saveAndFlush(member);

        assertThat(memberRepository.findByGroupOrderId(saved.getId())).hasSize(1);
    }

    @Test
    void giftCardUniqueCodeAndRedemption() {
        GiftCard card = new GiftCard();
        card.setCode("GC-TEST1");
        card.setAmount(new BigDecimal("500.00"));
        card.setBalance(new BigDecimal("500.00"));
        card.setStatus(GiftCard.STATUS_ACTIVE);
        giftCardRepository.saveAndFlush(card);

        assertThat(giftCardRepository.findByCodeAndStatus("GC-TEST1", GiftCard.STATUS_ACTIVE)).isPresent();
    }

    @Test
    void subscriptionPersists() {
        Subscription sub = new Subscription();
        sub.setCustomerId(1L);
        sub.setRestaurantId(2L);
        sub.setPlan("MONTHLY");
        sub.setStatus(Subscription.STATUS_ACTIVE);
        subscriptionRepository.saveAndFlush(sub);

        assertThat(subscriptionRepository.findByCustomerIdAndStatus(1L, Subscription.STATUS_ACTIVE)).hasSize(1);
    }
}
