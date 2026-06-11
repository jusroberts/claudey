defmodule WigglebotServer.Finance.AnomalyReport do
  @moduledoc """
  Weekly spending-anomaly report.

  Stateless: each run pulls the last week of transactions plus a trailing
  baseline window and flags
    - categories/payees whose weekly spend is a robust outlier
      (modified z-score on median/MAD of weekly totals),
    - first-time payees above a floor amount,
    - likely duplicate charges (same payee+amount within 3 days).

  The flags get a short LLM summary (plain numeric fallback when llama is
  down), are pushed to the phone, and logged so the agent can answer
  "what did last week's report say?".
  """

  require Logger

  alias WigglebotServer.{Finance, LlamaClient, Push, Reports}

  @window_days 7
  @baseline_weeks 26
  # modified z-score threshold (0.6745 * (x - median) / MAD)
  @z_threshold 3.5
  # ignore deviations smaller than this many cents regardless of z
  @min_deviation_cents 2_500
  # new payees below this spend aren't worth a flag
  @new_payee_floor_cents 5_000

  def run_weekly do
    today = Date.utc_today()
    window_start = Date.add(today, -@window_days)
    baseline_start = Date.add(window_start, -@baseline_weeks * 7)

    case Finance.all_transactions(baseline_start, today) do
      {:ok, txns} ->
        txns = Enum.reject(txns, &(&1.transfer || &1.date == nil))
        {window, baseline} = Enum.split_with(txns, &(Date.compare(&1.date, window_start) != :lt))

        flags =
          category_outliers(window, baseline) ++
            payee_outliers(window, baseline) ++
            new_payees(window, baseline) ++
            duplicate_charges(window)

        deliver(flags, window)

      {:error, reason} ->
        Logger.warning("AnomalyReport: could not fetch transactions: #{reason}")
        :error
    end
  end

  # ── Flag generation ─────────────────────────────────────────────────────────

  defp category_outliers(window, baseline) do
    outliers(window, baseline, & &1.category, "category")
  end

  defp payee_outliers(window, baseline) do
    outliers(window, baseline, & &1.payee, "payee")
  end

  defp outliers(window, baseline, key_fn, label) do
    current = spend_by(window, key_fn)
    baseline_weekly = weekly_spend_by(baseline, key_fn)

    current
    |> Enum.flat_map(fn {key, cents} ->
      weeks = Map.get(baseline_weekly, key, [])

      # Only judge keys with enough history; new ones are handled separately.
      if length(weeks) >= 4 do
        med = median(weeks)
        mad = median(Enum.map(weeks, &abs(&1 - med)))
        deviation = cents - med

        z = if mad > 0, do: 0.6745 * deviation / mad, else: nil
        spike_without_mad = mad == 0 and cents > 2 * med

        if deviation >= @min_deviation_cents and ((z && z >= @z_threshold) or spike_without_mad) do
          [
            "#{String.capitalize(label)} \"#{key}\": #{Finance.format_money(cents)} this week " <>
              "vs a typical #{Finance.format_money(med)}/week"
          ]
        else
          []
        end
      else
        []
      end
    end)
  end

  defp new_payees(window, baseline) do
    known = baseline |> Enum.map(& &1.payee) |> Enum.reject(&is_nil/1) |> MapSet.new()

    window
    |> spend_by(& &1.payee)
    |> Enum.flat_map(fn {payee, cents} ->
      if payee not in [nil, ""] and not MapSet.member?(known, payee) and
           cents >= @new_payee_floor_cents do
        ["New payee \"#{payee}\": #{Finance.format_money(cents)}"]
      else
        []
      end
    end)
  end

  defp duplicate_charges(window) do
    window
    |> Enum.filter(&(&1.amount < 0 and &1.payee not in [nil, ""]))
    |> Enum.group_by(&{&1.payee, &1.amount})
    |> Enum.flat_map(fn {{payee, amount}, txns} ->
      dates = txns |> Enum.map(& &1.date) |> Enum.sort(Date)

      close_pair? =
        dates
        |> Enum.chunk_every(2, 1, :discard)
        |> Enum.any?(fn [a, b] -> Date.diff(b, a) <= 3 end)

      if length(txns) > 1 and close_pair? do
        [
          "Possible duplicate: #{length(txns)}× #{Finance.format_money(abs(amount))} " <>
            "to \"#{payee}\" within a few days"
        ]
      else
        []
      end
    end)
  end

  # ── Delivery ────────────────────────────────────────────────────────────────

  defp deliver([], _window) do
    Logger.info("AnomalyReport: no anomalies this week")
    Reports.log("finance_anomaly", "Weekly spending check", "No anomalies detected.")
    :ok
  end

  defp deliver(flags, window) do
    total_spend =
      window |> Enum.filter(&(&1.amount < 0)) |> Enum.map(&abs(&1.amount)) |> Enum.sum()

    detail =
      "Total spend this week: #{Finance.format_money(total_spend)}\n\n" <>
        Enum.map_join(flags, "\n", &("• " <> &1))

    title = "Spending check: #{length(flags)} thing#{if length(flags) == 1, do: "", else: "s"} to look at"
    body = summarize(flags) || detail

    Reports.log("finance_anomaly", title, detail)
    Push.notify(title, body, %{"channel" => "wigglebot_finance"})
    :ok
  end

  defp summarize(flags) do
    prompt = """
    Summarize these personal-spending anomalies in 2-3 friendly sentences for a
    phone notification. No preamble, no advice, just what stands out:

    #{Enum.map_join(flags, "\n", &("- " <> &1))}
    """

    case LlamaClient.chat([%{role: "user", content: prompt}]) do
      {:ok, %{"choices" => [%{"message" => %{"content" => content}} | _]}}
      when is_binary(content) and content != "" ->
        String.trim(content)

      _ ->
        nil
    end
  end

  # ── Stats helpers ───────────────────────────────────────────────────────────

  # Total spend (positive cents) per key over the whole list.
  defp spend_by(txns, key_fn) do
    txns
    |> Enum.filter(&(&1.amount < 0))
    |> Enum.group_by(key_fn)
    |> Map.new(fn {k, ts} -> {k, ts |> Enum.map(&abs(&1.amount)) |> Enum.sum()} end)
  end

  # Per key: list of weekly totals (cents) across the baseline period,
  # including zero weeks, so sporadic spending gets a low median.
  defp weekly_spend_by(txns, key_fn) do
    spending = Enum.filter(txns, &(&1.amount < 0))

    case spending do
      [] ->
        %{}

      _ ->
        {min_date, max_date} = Enum.min_max_by(spending, & &1.date, Date) |> dates()
        week_count = max(div(Date.diff(max_date, min_date), 7) + 1, 1)

        by_key_week =
          spending
          |> Enum.group_by(&{key_fn.(&1), div(Date.diff(&1.date, min_date), 7)})
          |> Map.new(fn {k, ts} -> {k, ts |> Enum.map(&abs(&1.amount)) |> Enum.sum()} end)

        spending
        |> Enum.map(key_fn)
        |> Enum.uniq()
        |> Map.new(fn key ->
          weeks = for w <- 0..(week_count - 1), do: Map.get(by_key_week, {key, w}, 0)
          {key, weeks}
        end)
    end
  end

  defp dates({min_txn, max_txn}), do: {min_txn.date, max_txn.date}

  defp median([]), do: 0

  defp median(list) do
    sorted = Enum.sort(list)
    n = length(sorted)

    if rem(n, 2) == 1 do
      Enum.at(sorted, div(n, 2))
    else
      (Enum.at(sorted, div(n, 2) - 1) + Enum.at(sorted, div(n, 2))) / 2
    end
  end
end
