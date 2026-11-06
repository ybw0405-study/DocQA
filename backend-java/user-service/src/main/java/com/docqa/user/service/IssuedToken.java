package com.docqa.user.service;

public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
}
