defmodule WigglebotServer.Finance.ActualClient do
  @moduledoc """
  Client for the actual-http-api sidecar (jhonderson/actual-http-api), which
  wraps the official Actual Budget Node API.

  Configure via env vars (see runtime.exs):
    ACTUAL_API_URL  — e.g. http://127.0.0.1:5007
    ACTUAL_API_KEY  — the wrapper's self-chosen API key
    ACTUAL_SYNC_ID  — budget Sync ID (Actual: Settings → Advanced → Sync ID)

  All amounts are integer minor units (cents); negative = money out.
  """

  @timeout_ms 30_000

  def configured? do
    cfg = config()
    cfg[:url] not in [nil, ""] and cfg[:api_key] not in [nil, ""] and
      cfg[:sync_id] not in [nil, ""]
  end

  @doc "All accounts: maps with \"id\", \"name\", \"offbudget\", \"closed\"."
  def accounts do
    get("/accounts")
  end

  @doc "Balance in cents for one account, optionally as of cutoff_date (Date)."
  def balance(account_id, cutoff_date \\ nil) do
    query = if cutoff_date, do: [cutoff_date: Date.to_iso8601(cutoff_date)], else: []
    get("/accounts/#{account_id}/balance", query)
  end

  @doc "Transactions for one account between two Dates (until optional)."
  def transactions(account_id, since_date, until_date \\ nil) do
    query =
      [since_date: Date.to_iso8601(since_date)] ++
        if until_date, do: [until_date: Date.to_iso8601(until_date)], else: []

    get("/accounts/#{account_id}/transactions", query)
  end

  @doc "Category id → name map."
  def category_names do
    with {:ok, categories} <- get("/categories") do
      {:ok, Map.new(categories, &{&1["id"], &1["name"]})}
    end
  end

  @doc "Payee id → name map."
  def payee_names do
    with {:ok, payees} <- get("/payees") do
      {:ok, Map.new(payees, &{&1["id"], &1["name"]})}
    end
  end

  @doc "Budget summary for a month string like \"2026-06\"."
  def budget_month(month) do
    get("/months/#{month}")
  end

  defp get(path, query \\ []) do
    cfg = config()

    if configured?() do
      url = "#{String.trim_trailing(cfg[:url], "/")}/v1/budgets/#{cfg[:sync_id]}#{path}"

      case Req.get(url,
             params: query,
             headers: [{"x-api-key", cfg[:api_key]}],
             receive_timeout: @timeout_ms
           ) do
        {:ok, %{status: 200, body: %{"data" => data}}} ->
          {:ok, data}

        {:ok, %{status: 200, body: body}} ->
          {:ok, body}

        {:ok, %{status: status, body: body}} ->
          {:error, "actual-http-api HTTP #{status}: #{inspect(body)}"}

        {:error, reason} ->
          {:error, "actual-http-api unreachable: #{inspect(reason)}"}
      end
    else
      {:error,
       "Actual Budget is not configured on the server (ACTUAL_API_URL / ACTUAL_API_KEY / ACTUAL_SYNC_ID)"}
    end
  end

  defp config, do: Application.get_env(:wigglebot_server, :actual_api, [])
end
