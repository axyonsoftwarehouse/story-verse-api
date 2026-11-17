package com.storyverse.api.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.storyverse.api.auth.AuthTokens;

/** Itens e imagens da loja no painel. Só superadmin; as permissões são checadas no serviço. */
@RestController
@RequestMapping("/api/v1/admin/shop")
public class ShopItemController {

	private final ShopItemService service;

	public ShopItemController(ShopItemService service) {
		this.service = service;
	}

	@GetMapping("/items")
	public List<Map<String, Object>> list(
			@RequestParam(required = false) String category,
			@RequestParam(required = false) String status,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.list(AuthTokens.bearer(authorization), category, status);
	}

	@PostMapping("/items")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.create(AuthTokens.bearer(authorization), body);
	}

	@PutMapping("/items/{id}")
	public Map<String, Object> update(
			@PathVariable UUID id,
			@RequestBody Map<String, Object> body,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.update(AuthTokens.bearer(authorization), id, body);
	}

	@DeleteMapping("/items/{id}")
	public ResponseEntity<Void> delete(
			@PathVariable UUID id,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		service.delete(AuthTokens.bearer(authorization), id);
		return ResponseEntity.noContent().build();
	}

	/** Multipart {@code file}; {@code kind} = frame, frame_animated, badge ou cover. */
	@PostMapping("/images")
	public Map<String, String> uploadImage(
			@RequestParam(required = false) String kind,
			@RequestParam(value = "file", required = false) MultipartFile file,
			@RequestHeader(value = "Authorization", required = false) String authorization) {
		return service.uploadImage(AuthTokens.bearer(authorization), kind, file);
	}
}
