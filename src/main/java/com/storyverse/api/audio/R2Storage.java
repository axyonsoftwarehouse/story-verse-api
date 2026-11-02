package com.storyverse.api.audio;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.storyverse.api.auth.AuthException;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Cloudflare R2 (API compatível com S3) para os áudios do catálogo. O bucket é privado:
 * o backend só entrega URLs assinadas de curta duração. Sem as variáveis configuradas, as
 * rotas de upload respondem 503 e o restante do cadastro continua funcionando.
 */
@Service
public class R2Storage {

	private final S3Presigner presigner;
	private final String bucket;

	public R2Storage(
			@Value("${app.r2.endpoint:}") String endpoint,
			@Value("${app.r2.access-key:}") String accessKey,
			@Value("${app.r2.secret-key:}") String secretKey,
			@Value("${app.r2.bucket:catalog-audio}") String bucket,
			@Value("${app.r2.region:auto}") String region) {
		String safeEndpoint = text(endpoint);
		String safeAccessKey = text(accessKey);
		String safeSecretKey = text(secretKey);
		this.bucket = text(bucket) == null ? "catalog-audio" : text(bucket);
		if (safeEndpoint == null || safeAccessKey == null || safeSecretKey == null) {
			this.presigner = null;
			return;
		}
		String safeRegion = text(region) == null ? "auto" : text(region);
		this.presigner = S3Presigner.builder()
				.region(Region.of(safeRegion))
				.endpointOverride(URI.create(safeEndpoint))
				.credentialsProvider(StaticCredentialsProvider.create(
						AwsBasicCredentials.create(safeAccessKey, safeSecretKey)))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.build();
	}

	public boolean configured() {
		return presigner != null;
	}

	public String bucket() {
		return bucket;
	}

	public String presignPut(String key, String contentType, Duration ttl) {
		S3Presigner signer = requireConfigured();
		PutObjectRequest object = PutObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.contentType(contentType)
				.build();
		return signer.presignPutObject(request -> request
				.signatureDuration(ttl)
				.putObjectRequest(object)).url().toString();
	}

	public String presignGet(String key, Duration ttl) {
		S3Presigner signer = requireConfigured();
		GetObjectRequest object = GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build();
		return signer.presignGetObject(request -> request
				.signatureDuration(ttl)
				.getObjectRequest(object)).url().toString();
	}

	private S3Presigner requireConfigured() {
		if (presigner == null) {
			throw new AuthException(503, "Armazenamento de áudio não configurado.");
		}
		return presigner;
	}

	private static String text(String value) {
		if (value == null) return null;
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}

	@PreDestroy
	public void close() {
		if (presigner != null) {
			presigner.close();
		}
	}
}
