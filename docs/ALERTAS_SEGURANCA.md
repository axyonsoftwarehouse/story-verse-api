# Alertas de segurança — API

Os eventos abaixo são emitidos por `SecurityEvents` sem PII no log e com a tag
`security.event` no Sentry:

| Evento | Quando | Nível |
|---|---|---|
| `auth.refresh_reuse` | refresh token antigo reapareceu; sessão revogada | warning |
| `auth.password_reset` | senha redefinida por link | info |
| `auth.password_changed` | senha trocada logado | info |
| `account.deleted` | conta excluída pelo próprio usuário | warning |

## Regras recomendadas no painel do Sentry

1. **Crítico:** qualquer evento `security.event:auth.refresh_reuse` (possível roubo de token).
2. **Alto:** pico de `security.event:account.deleted` ou `security.event:auth.password_reset`
   acima do normal por conta/hora.
3. **Alto:** taxa de respostas 5xx acima de 2% por 5 minutos.
4. **Médio:** pico de 401/403/429 por IP/conta (possível brute force ou abuso).
5. **Médio:** falha repetida de envio de e-mail (SMTP) e de provedores de IA.
6. **Médio:** uso de IA bloqueado por orçamento/limite em volume anormal.

## Operação

- O Sentry já remove usuário, requisição, extras e breadcrumbs; não inclua e-mail, token ou
  conteúdo de conversa na mensagem/tag.
- Alertas devem ir para o canal da equipe e ter um runbook de resposta:
  - reuso de refresh: revogar sessões da conta, revisar acessos recentes e notificar o titular;
  - 5xx: verificar readiness/banco e último deploy;
  - 429: verificar origem, janela e se é ataque distribuído;
  - exclusão/reset anormal: confirmar com o titular e revisar auditoria administrativa.
- A configuração das regras no painel do Sentry é uma etapa manual; este repositório só garante
  a emissão consistente dos eventos.
