# HeatingAgent AI

Agent ogrzewania zintegrowany z LLM (Large Language Model) używający frameworka Koog.

## Opis

HeatingAgent AI to wersja agenta ogrzewania, która używa LLM do podejmowania decyzji zamiast klasycznych reguł. Agent:
- Pobiera stan środowiska z OrSimulator
- Używa LLM do podejmowania decyzji o włączeniu/wyłączeniu ogrzewania
- Wysyła i odbiera komunikaty w języku naturalnym od innych agentów
- Wykonuje akcje przez Tools dostępne dla LLM

## Wymagania

- Kotlin 2.2.21+
- Ktor 3.3.2+
- Koog 0.5.1+
- OrSimulator działający na porcie 8080

## Konfiguracja

### Zmienne środowiskowe

Ustaw API key dla wybranego providera LLM:

```bash
export OPENAI_API_KEY="your-openai-api-key"
# lub
export GOOGLE_API_KEY="your-google-api-key"
# lub
export ANTHROPIC_API_KEY="your-anthropic-api-key"
```

### Konfiguracja w application.yaml

```yaml
heating:
  agent:
    simulator:
      url: "http://localhost:8080"
```

## Uruchomienie

```bash
./gradlew run
```

Agent uruchomi się na porcie 8061 (domyślnie) i zacznie komunikować się z OrSimulator na porcie 8080.

## Architektura

### Komponenty

1. **HeatingAgent** - główna klasa agenta
   - `decisionLoop()` - cykliczne podejmowanie decyzji (co 10 sekund)
   - `messageCheckLoop()` - sprawdzanie nowych wiadomości NL (co 3 sekundy)

2. **SimulatorClient** - klient HTTP do komunikacji z OrSimulator
   - Pobieranie stanu środowiska
   - Sterowanie ogrzewaniem
   - Wysyłanie/odbieranie wiadomości NL

3. **Tools dla LLM**:
   - `turnOnHeatingTool()` - włącza ogrzewanie
   - `turnOffHeatingTool()` - wyłącza ogrzewanie
   - `sendMessageTool()` - wysyła komunikat NL do innego agenta

### Prompt systemowy

Agent używa promptu systemowego, który definiuje:
- Zadania agenta
- Dostępne akcje (Tools)
- Zasady działania
- Listę innych agentów do komunikacji

### Komunikacja NL

Agent może:
- Wysyłać komunikaty do innych agentów przez endpoint `/api/environment/agents/messages`
- Odbierać komunikaty przez endpoint `/api/environment/agents/messages/{agentId}`
- Przetwarzać komunikaty przez LLM i reagować odpowiednimi akcjami

## Przykłady użycia

### Wysyłanie komunikatu do innego agenta

Agent może wysłać komunikat np. do WindowBlindsAgent:
```
"Włączyłem ogrzewanie, ponieważ temperatura w pokoju 208 wynosi 19°C i są 2 osoby. 
Możesz zamknąć rolety, aby pomóc w ogrzaniu pokoju?"
```

### Odbieranie i przetwarzanie komunikatów

Gdy agent otrzyma komunikat, LLM analizuje go i decyduje czy zareagować:
```
Komunikat: "Za 15 minut jest spotkanie w pokoju 208. Przygotuj pokój."
Odpowiedź: Agent włączy ogrzewanie i może wysłać potwierdzenie.
```

## Integracja z OrSimulator

Agent komunikuje się z OrSimulator przez następujące endpointy:

- `GET /api/environment/state` - pobiera stan środowiska
- `GET /api/environment/heating` - pobiera stan ogrzewania
- `POST /api/environment/heating/control` - steruje ogrzewaniem
- `POST /api/environment/agents/messages` - wysyła wiadomość NL
- `GET /api/environment/agents/messages/{agentId}` - pobiera wiadomości dla agenta
- `GET /api/environment/agents/messages/{agentId}/new` - pobiera nowe wiadomości

## Różnice względem klasycznego HeatingAgent

- **Brak sztywnych reguł** - decyzje podejmowane przez LLM
- **Komunikacja NL** - może komunikować się z innymi agentami w języku naturalnym
- **Adaptacyjność** - LLM może dostosować się do różnych sytuacji
- **Większa złożoność** - wymaga API key i może generować koszty

## Troubleshooting

### Agent nie podejmuje decyzji

- Sprawdź czy OrSimulator działa na porcie 8080
- Sprawdź czy API key jest ustawiony
- Sprawdź logi w konsoli

### Błędy komunikacji z LLM

- Sprawdź czy API key jest poprawny
- Sprawdź czy masz dostęp do internetu
- Sprawdź limity API (rate limiting)

### Komunikaty NL nie działają

- Sprawdź czy endpoint `/api/environment/agents/messages` jest dostępny w OrSimulator
- Sprawdź czy inne agenty wysyłają komunikaty
- Sprawdź logi w konsoli
