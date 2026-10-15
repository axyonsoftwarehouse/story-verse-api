package com.storyverse.api.storage;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.storyverse.api.auth.AuthService;
import com.storyverse.api.auth.AuthTokens;

@RestController
@RequestMapping("/api/v1/storage")
public class StorageController {

	private final StorageService storage;
	private final AuthService auth;

	public StorageController(StorageService storage, AuthService auth) {
		this.storage = storage;
		this.auth = auth;
	}

	@PostMapping("/{bucket}/object")
	public ResponseEntity<Void> upload(
			@PathVariable String bucket,
			@RequestParam String path,
			@RequestHeader(value = "Content-Type", required = false) String contentType,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody byte[] body) {
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		storage.write(userId, StorageBucket.from(bucket), path, contentType, body);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/{bucket}/object")
	public ResponseEntity<byte[]> download(
			@PathVariable String bucket,
			@RequestParam String path,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		StorageBucket storageBucket = StorageBucket.from(bucket);
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		String own = StorageService.requireOwn(userId, path);
		return storage.read(storageBucket, own)
				.map(data -> ResponseEntity.ok().contentType(typeFor(own)).body(data))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{bucket}/object")
	public ResponseEntity<Void> delete(
			@PathVariable String bucket,
			@RequestParam String path,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		storage.delete(userId, StorageBucket.from(bucket), path);
		return ResponseEntity.noContent().build();
	}

	/** Leitura pública (só buckets públicos, como `profile-media`). Não pede sessão. */
	@GetMapping("/public/{bucket}/{*path}")
	public ResponseEntity<byte[]> publicDownload(@PathVariable String bucket, @PathVariable String path) {
		StorageBucket storageBucket = StorageBucket.from(bucket);
		if (!storageBucket.publiclyReadable()) {
			return ResponseEntity.notFound().build();
		}
		return storage.read(storageBucket, path)
				.map(data -> ResponseEntity.ok()
						.contentType(typeFor(path))
						.cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic())
						.body(data))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	private static MediaType typeFor(String path) {
		String lower = path.toLowerCase();
		if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
		if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
		if (lower.endsWith(".gz")) return MediaType.parseMediaType("application/gzip");
		if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
		return MediaType.APPLICATION_OCTET_STREAM;
	}
}
