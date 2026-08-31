package com.bhukkad.identity;

import com.bhukkad.identity.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 1 identity depth: V4 migration (JOINED user hierarchy +
 * profile tables) and repositories against PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdentityDepthPostgresIntegrationTest extends AbstractIdentityPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private DeviceTokenRepository deviceTokenRepository;
    @Autowired private ConsentRecordRepository consentRepository;
    @Autowired private FavoriteRestaurantRepository favoriteRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private MembershipPlanRepository planRepository;
    @Autowired private CustomerMembershipRepository membershipRepository;
    @Autowired private AffiliateCodeRepository affiliateCodeRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM affiliate_referrals");
        jdbcTemplate.update("DELETE FROM affiliate_codes");
        jdbcTemplate.update("DELETE FROM customer_memberships");
        jdbcTemplate.update("DELETE FROM membership_plans");
        jdbcTemplate.update("DELETE FROM favorite_restaurants");
        jdbcTemplate.update("DELETE FROM consent_records");
        jdbcTemplate.update("DELETE FROM device_tokens");
        jdbcTemplate.update("DELETE FROM user_referral_codes");
        jdbcTemplate.update("DELETE FROM restaurant_owners");
        jdbcTemplate.update("DELETE FROM delivery_agents");
        jdbcTemplate.update("DELETE FROM admins");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM tenants");
    }

    @Test
    void migration_appliedV4Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('users','restaurant_owners','delivery_agents','admins'," +
                        "'device_tokens','consent_records','favorite_restaurants','tenants'," +
                        "'membership_plans','customer_memberships','affiliate_codes','affiliate_referrals')",
                Integer.class);
        assertThat(tables).isEqualTo(12);
    }

    @Test
    void joinedInheritance_persistsOwnerAndAgent() {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setRole(User.UserRole.RESTAURANT_OWNER);
        owner.setEmail("owner@b.com");
        owner.setFullName("Owner A");
        owner.setBusinessLicense("LIC-1");
        RestaurantOwner savedOwner = userRepository.saveAndFlush(owner);

        DeliveryAgent agent = new DeliveryAgent();
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        agent.setEmail("agent@b.com");
        agent.setFullName("Agent A");
        DeliveryAgent savedAgent = userRepository.saveAndFlush(agent);

        // Registry row + sub-table row both written (JOINED inheritance).
        Integer ownerSub = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM restaurant_owners WHERE id = ?", Integer.class, savedOwner.getId());
        assertThat(ownerSub).isEqualTo(1);
        Integer agentSub = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM delivery_agents WHERE id = ?", Integer.class, savedAgent.getId());
        assertThat(agentSub).isEqualTo(1);

        User readBack = userRepository.findById(savedOwner.getId()).orElseThrow();
        assertThat(readBack).isInstanceOf(RestaurantOwner.class);
        assertThat(((RestaurantOwner) readBack).getBusinessLicense()).isEqualTo("LIC-1");
    }

    @Test
    void deviceTokensAndConsentPersist() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        DeliveryAgent saved = userRepository.saveAndFlush(agent);

        DeviceToken token = new DeviceToken();
        token.setUserId(saved.getId());
        token.setToken("fcm-abc");
        token.setDeviceType("ANDROID");
        deviceTokenRepository.saveAndFlush(token);

        ConsentRecord consent = new ConsentRecord();
        consent.setUserId(saved.getId());
        consent.setPurpose("DATA_SHARING");
        consent.setGranted(true);
        consentRepository.saveAndFlush(consent);

        assertThat(deviceTokenRepository.findByUserId(saved.getId())).hasSize(1);
        assertThat(consentRepository.findByUserIdAndPurpose(saved.getId(), "DATA_SHARING")).isPresent();
    }

    @Test
    void favoritesAndTenantsPersist() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        DeliveryAgent saved = userRepository.saveAndFlush(agent);

        FavoriteRestaurant favorite = new FavoriteRestaurant();
        favorite.setCustomerId(saved.getId());
        favorite.setRestaurantId(10L);
        favoriteRepository.saveAndFlush(favorite);

        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setDomain("acme.food");
        tenantRepository.saveAndFlush(tenant);

        assertThat(favoriteRepository.findByCustomerId(saved.getId())).hasSize(1);
        assertThat(tenantRepository.findByDomain("acme.food")).isPresent();
    }

    @Test
    void membershipAndAffiliatePersist() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        DeliveryAgent saved = userRepository.saveAndFlush(agent);

        MembershipPlan plan = new MembershipPlan();
        plan.setName("Gold");
        plan.setTier("gold");
        plan.setPrice(new BigDecimal("299.00"));
        planRepository.saveAndFlush(plan);

        CustomerMembership membership = new CustomerMembership();
        membership.setCustomerId(saved.getId());
        membership.setPlanId(plan.getId());
        membership.setStatus("ACTIVE");
        membership.setStartedAt(LocalDateTime.now());
        membership.setExpiresAt(LocalDateTime.now().plusDays(30));
        membershipRepository.saveAndFlush(membership);

        AffiliateCode affiliateCode = new AffiliateCode();
        affiliateCode.setRestaurantId(5L);
        affiliateCode.setCode("ACME10");
        affiliateCodeRepository.saveAndFlush(affiliateCode);

        assertThat(planRepository.findByActiveTrue()).hasSize(1);
        assertThat(membershipRepository.findFirstByCustomerIdAndStatusOrderByExpiresAtDesc(
                saved.getId(), "ACTIVE")).isPresent();
        assertThat(affiliateCodeRepository.findByCode("ACME10")).isPresent();
    }
}
