package com.bombbellannouncer.protocol;

public record ReporterSubmitIntentResponse(
	boolean success,
	String message,
	ReporterRole role,
	boolean eligible,
	boolean canSubmit,
	long submitWindowSequence
) {
	public static ReporterSubmitIntentResponse failure(String message, ReporterRole role) {
		ReporterRole normalizedRole = role == null ? ReporterRole.INELIGIBLE : role;
		return new ReporterSubmitIntentResponse(false, message, normalizedRole, normalizedRole.eligible(), false, 0L);
	}

	public static ReporterSubmitIntentResponse success(String message, ReporterRole role, boolean canSubmit, long submitWindowSequence) {
		ReporterRole normalizedRole = role == null ? ReporterRole.INELIGIBLE : role;
		return new ReporterSubmitIntentResponse(true, message, normalizedRole, normalizedRole.eligible(), canSubmit, Math.max(0L, submitWindowSequence));
	}
}
