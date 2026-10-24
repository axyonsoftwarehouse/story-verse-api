package com.storyverse.api.storage;

import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
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
			HttpServletRequest request) throws IOException {
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		StorageBucket selected = StorageBucket.from(bucket);
		StorageService.requireOwn(userId, path);
		if (request.getContentLengthLong() > selected.maxBytes()) throw new com.storyverse.api.auth.AuthException(413, "Arquivo grande demais.");
		storage.write(userId, selected, path, contentType, request.getInputStream(), request.getContentLengthLong());
		return ResponseEntity.ok().build();
	}

	@GetMapping("/{bucket}/object")
	public ResponseEntity<Resource> download(
			@PathVariable String bucket,
			@RequestParam String path,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		StorageBucket storageBucket = StorageBucket.from(bucket);
		UUID userId = auth.authenticate(AuthTokens.bearer(authorization));
		return storage.fileFor(userId, storageBucket, path)
				.map(file -> {
					Resource resource = new FileSystemResource(file);
					return ResponseEntity.ok()
							.contentType(typeFor(path))
							.cacheControl(CacheControl.noStore())
							.body(resource);
				})
				.orElseGet(() -> ResponseEntity.<Resource>notFound().build());
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
	public ResponseEntity<Resource> publicDownload(@PathVariable String bucket, @PathVariable String path) {
		StorageBucket storageBucket = StorageBucket.from(bucket);
		if (!storageBucket.publiclyReadable()) {
			return ResponseEntity.notFound().build();
		}
		// No padrão {*path} do Spring, o valor vem com a barra inicial ("/uid/arquivo").
		String relative = path.startsWith("/") ? path.substring(1) : path;
		return storage.file(storageBucket, relative)
				.map(file -> {
					Resource resource = new FileSystemResource(file);
					return ResponseEntity.ok()
							.contentType(typeFor(path))
							.cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic())
							.body(resource);
				})
				.orElseGet(() -> ResponseEntity.<Resource>notFound().build());
	}

	private static MediaType typeFor(String path) {
		String lower = path.toLowerCase();
		if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
		if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
		if (lower.endsWith(".gz")) return MediaType.parseMediaType("application/gzip");
		if (lower.endsWith(".zip")) return MediaType.parseMediaType("application/zip");
		if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
		return MediaType.APPLICATION_OCTET_STREAM;
	}
}
