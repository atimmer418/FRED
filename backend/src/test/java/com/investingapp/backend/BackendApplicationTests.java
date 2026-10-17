package com.investingapp.backend;

import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application context: the cheapest check that every bean still resolves and every
 * migration still applies.
 *
 * <p>It needs a database and a profile that defines one. `ci` is that profile: a local MySQL that
 * CI provisions, with Plaid and Pinecone stubbed. Without @ActiveProfiles the base profile applies,
 * which has no datasource and no jwt.secret, and the test fails for want of config rather than for
 * any real defect. The `test` profile is not an option: it describes the deployed Railway
 * environment, so running this from a laptop would migrate a live database.
 *
 * <p>{@link RequiresCiDatabase} skips the test, with its reason, where no such database exists.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
