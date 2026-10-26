package watch.nabu.infrastructure.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One element from the GitHub {@code GET /user/emails} JSON array.
 *
 * <pre>
 * [
 *   { "email": "user@example.com",                       "primary": true,  "verified": true  },
 *   { "email": "12345+login@users.noreply.github.com",   "primary": false, "verified": true  }
 * ]
 * </pre>
 *
 * <p>We only accept an entry that is <em>both</em> {@code primary=true} and
 * {@code verified=true}.  Accepting an unverified email as an account identity
 * would allow account-hijacking via unverified email addresses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubEmailApiResponse(

        @JsonProperty("email")
        String email,

        @JsonProperty("primary")
        boolean primary,

        @JsonProperty("verified")
        boolean verified
) {
}
