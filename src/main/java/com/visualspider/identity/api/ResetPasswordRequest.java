package com.visualspider.identity.api;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank String password) {
}
