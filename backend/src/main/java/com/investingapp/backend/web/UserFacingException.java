package com.investingapp.backend.web;

/**
 * A refusal whose message was written for the person using the app, and is safe to show them.
 *
 * <p>This is the ONLY kind of exception whose text may go into a reply. Every other exception is
 * logged and answered with a fixed sentence, because its text is written for a developer and can
 * carry the broker's or the bank's raw answer, a web address with an account id in it, SQL, or a
 * Java class name.
 *
 * <p>So the message must be a plain sentence with nothing in it but what the user already knows:
 * no ids, no email addresses, no text from another system, no text from another exception.
 */
public class UserFacingException extends RuntimeException {

    public UserFacingException(String message) {
        super(message);
    }
}
