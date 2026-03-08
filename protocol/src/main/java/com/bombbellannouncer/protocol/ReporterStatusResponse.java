package com.bombbellannouncer.protocol;

public record ReporterStatusResponse(
	boolean success,
	String message,
	ReporterRole role,
	boolean eligible,
	boolean canSubmit,
	long submitWindowSequence
) {
	public static ReporterStatusResponse failure(String message, ReporterRole role) {
		ReporterRole normalizedRole = role == null ? ReporterRole.INELIGIBLE : role;
		return new ReporterStatusResponse(false, message, normalizedRole, normalizedRole.eligible(), false, 0L);
	}

	public static ReporterStatusResponse success(String message, ReporterRole role) {
		return success(message, role, false, 0L);
	}

	public static ReporterStatusResponse success(String message, ReporterRole role, boolean canSubmit, long submitWindowSequence) {
		ReporterRole normalizedRole = role == null ? ReporterRole.INELIGIBLE : role;
		return new ReporterStatusResponse(true, message, normalizedRole, normalizedRole.eligible(), canSubmit, Math.max(0L, submitWindowSequence));
	}
}
