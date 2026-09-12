package com.bhukkad.testsupport;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base test data builder using Java's built-in utilities for generating test data.
 * Services can extend this to create domain-specific builders.
 */
public abstract class TestDataBuilder<T> {

	protected final Random random = new Random();

	/**
	 * Build a single instance.
	 */
	public abstract T build();

	/**
	 * Build multiple instances.
	 */
	@SuppressWarnings("unchecked")
	public T[] buildArray(int count) {
		T[] array = (T[]) new Object[count];
		for (int i = 0; i < count; i++) {
			array[i] = build();
		}
		return array;
	}

	/**
	 * Generate a random UUID string.
	 */
	protected String randomUuid() {
		return UUID.randomUUID().toString();
	}

	/**
	 * Generate a random email.
	 */
	protected String randomEmail() {
		return "test" + random.nextInt(10000) + "@example.com";
	}

	/**
	 * Generate a random phone number.
	 */
	protected String randomPhone() {
		return "+1555" + String.format("%07d", random.nextInt(10000000));
	}

	/**
	 * Generate a random name.
	 */
	protected String randomName() {
		String[] firstNames = {"John", "Jane", "Alice", "Bob", "Carol", "Dave", "Eve", "Frank"};
		String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis"};
		return firstNames[random.nextInt(firstNames.length)] + " " + lastNames[random.nextInt(lastNames.length)];
	}

	/**
	 * Generate a random string of given length.
	 */
	protected String randomString(int length) {
		String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(chars.charAt(random.nextInt(chars.length())));
		}
		return sb.toString();
	}

	/**
	 * Generate a random number within range.
	 */
	protected int randomInt(int min, int max) {
		return random.nextInt(max - min + 1) + min;
	}

	/**
	 * Generate a random BigDecimal for monetary values.
	 */
	protected java.math.BigDecimal randomMoney() {
		return java.math.BigDecimal.valueOf(random.nextDouble() * 1000);
	}

	/**
	 * Generate a random LocalDateTime within the last year.
	 */
	protected LocalDateTime randomRecentDateTime() {
		return LocalDateTime.now().minusDays(random.nextInt(365));
	}

	/**
	 * Generate a random boolean.
	 */
	protected boolean randomBoolean() {
		return random.nextBoolean();
	}

	/**
	 * Pick a random element from an enum.
	 */
	protected <E extends Enum<E>> E randomEnum(Class<E> enumClass) {
		E[] values = enumClass.getEnumConstants();
		return values[random.nextInt(values.length)];
	}

	/**
	 * Generate a supplier for lazy evaluation.
	 */
	protected <R> Supplier<R> lazy(Supplier<R> supplier) {
		return supplier;
	}
}