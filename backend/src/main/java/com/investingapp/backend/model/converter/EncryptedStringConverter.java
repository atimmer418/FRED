package com.investingapp.backend.model.converter;

import com.investingapp.backend.service.EncryptionService;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Keeps a text column encrypted in the database and readable in the entity.
 *
 * Put {@code @Convert(converter = EncryptedStringConverter.class)} on a field and every write goes
 * through {@link EncryptionService#encrypt} (AES-GCM, stored as {@code ENC:...}) and every read
 * through {@link EncryptionService#decrypt}. The code that uses the entity never sees ciphertext, so
 * it cannot forget to encrypt before a save or save a value twice encrypted.
 *
 * Two things follow from the encryption being random each time:
 * <ul>
 *   <li>the same value is stored differently in every row, so the column cannot be searched or
 *       compared in a query;</li>
 *   <li>a row written before the column was encrypted is still plain text. Reading one works on the
 *       dev and local profiles and fails everywhere else. That is {@code EncryptionService}'s rule,
 *       not this class's.</li>
 * </ul>
 *
 * The column must be wide enough for the ciphertext: about 45 characters more than the value, times
 * four thirds. 255 is plenty for anything short.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final EncryptionService encryptionService;

    public EncryptedStringConverter(EncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        return value == null ? null : encryptionService.encrypt(value);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return stored == null ? null : encryptionService.decrypt(stored);
    }
}
