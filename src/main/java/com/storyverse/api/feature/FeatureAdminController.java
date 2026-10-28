package com.storyverse.api.feature;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;
import com.storyverse.api.auth.AuthzService;

/** Concessão de features em beta; somente superadmin. */
@RestController
@RequestMapping("/api/v1/admin/features")
public class FeatureAdminController {

	private final AuthService auth;
	private final AuthzService authz;
	private final FeatureAccessService features;

	public FeatureAdminController(AuthService auth, AuthzService authz, FeatureAccessService features) {
		this.auth = auth;
		this.authz = authz;
		this.features = features;
	}

	@GetMapping
	public List<String> list(@RequestParam UUID userId,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		return features.features(actor, userId);
	}

	@GetMapping("/catalog")
	public List<FeatureCatalogEntry> catalog(
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return features.catalog(actor);
	}

	@GetMapping("/{feature}/users")
	public FeatureUsersPage users(@PathVariable String feature,
			@RequestParam(required = false) String cursor,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		return features.usersWith(actor, feature, cursor);
	}

	@PutMapping
	public Map<String, Object> set(@RequestBody FeatureGrantRequest request,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		if (request == null || request.userId() == null) {
			throw new com.storyverse.api.auth.AuthException(400, "Informe a conta.");
		}
		UUID actor = auth.authenticate(AuthTokens.bearer(authorization));
		authz.requireRole(actor, AuthzService.SUPERADMIN);
		boolean enabled = request.enabled() == null || request.enabled();
		features.grant(actor, request.userId(), request.feature(), enabled);
		return Map.of("userId", request.userId().toString(), "feature", request.feature(), "enabled", enabled);
	}
}
