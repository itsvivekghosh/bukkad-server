package com.bhukkad.search.dto.response;

public class AutocompleteSuggestion {
	public static final String TYPE_RESTAURANT = "RESTAURANT";
	public static final String TYPE_MENU_ITEM = "MENU_ITEM";

	private String text;
	private String type;

	public AutocompleteSuggestion() {
	}

	public AutocompleteSuggestion(String text, String type) {
		this.text = text;
		this.type = type;
	}

	public String getText() {
		return text;
	}

	public void setText(String text) {
		this.text = text;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		AutocompleteSuggestion that = (AutocompleteSuggestion) o;
		return java.util.Objects.equals(text, that.text) &&
				java.util.Objects.equals(type, that.type);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(text, type);
	}

	@Override
	public String toString() {
		return "AutocompleteSuggestion{" +
				"text='" + text + '\'' +
				", type='" + type + '\'' +
				'}';
	}

	public static class Builder {
		private String text;
		private String type;

		public Builder() {
		}

		public Builder text(String text) {
			this.text = text;
			return this;
		}

		public Builder type(String type) {
			this.type = type;
			return this;
		}

		public AutocompleteSuggestion build() {
			return new AutocompleteSuggestion(text, type);
		}
	}
}
