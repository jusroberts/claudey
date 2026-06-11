defmodule WigglebotServer.Finance do
  @moduledoc """
  Higher-level finance queries on top of the Actual Budget client.
  Returns human-readable strings suitable for agent tool results.
  """

  alias WigglebotServer.Finance.ActualClient

  @doc "Net worth: sum of all open account balances, split on/off budget."
  def net_worth do
    with {:ok, accounts} <- open_accounts(),
         {:ok, balances} <- balances_for(accounts) do
      total = balances |> Enum.map(&elem(&1, 1)) |> Enum.sum()

      {on_budget, off_budget} =
        Enum.split_with(balances, fn {acct, _} -> !acct["offbudget"] end)

      lines =
        balances
        |> Enum.sort_by(fn {_, cents} -> -cents end)
        |> Enum.map(fn {acct, cents} ->
          kind = if acct["offbudget"], do: " (off-budget)", else: ""
          "- #{acct["name"]}#{kind}: #{format_money(cents)}"
        end)

      summary =
        "Net worth: #{format_money(total)}\n" <>
          "On-budget: #{format_money(sum_balances(on_budget))}, " <>
          "off-budget: #{format_money(sum_balances(off_budget))}\n" <>
          Enum.join(lines, "\n")

      {:ok, summary}
    end
  end

  @doc "Balance of the account best matching `name_query` (e.g. \"credit card\")."
  def account_balance(name_query) do
    with {:ok, accounts} <- open_accounts() do
      case best_match(accounts, name_query) do
        nil ->
          names = accounts |> Enum.map(& &1["name"]) |> Enum.join(", ")
          {:error, "No account matching \"#{name_query}\". Accounts: #{names}"}

        account ->
          with {:ok, cents} <- ActualClient.balance(account["id"]) do
            {:ok, "#{account["name"]}: #{format_money(cents)}"}
          end
      end
    end
  end

  @doc "Spending by category for the last `days` days (default 30)."
  def spending_summary(days \\ 30) do
    since = Date.add(Date.utc_today(), -days)

    with {:ok, txns} <- all_transactions(since) do
      by_category =
        txns
        |> Enum.filter(&(&1.amount < 0))
        |> Enum.group_by(& &1.category)
        |> Enum.map(fn {cat, ts} ->
          {cat || "Uncategorized", ts |> Enum.map(& &1.amount) |> Enum.sum() |> abs()}
        end)
        |> Enum.sort_by(&elem(&1, 1), :desc)

      total = by_category |> Enum.map(&elem(&1, 1)) |> Enum.sum()

      lines = Enum.map(by_category, fn {cat, cents} -> "- #{cat}: #{format_money(cents)}" end)

      {:ok,
       "Spending over the last #{days} days: #{format_money(total)}\n" <>
         Enum.join(lines, "\n")}
    end
  end

  @doc """
  All transactions since `since_date` across open on-budget accounts, with
  payee/category names resolved. Returns list of maps:
  %{date: Date, amount: cents, payee: name, category: name, account: name, notes: s}
  """
  def all_transactions(since_date, until_date \\ nil) do
    with {:ok, accounts} <- open_accounts(),
         {:ok, categories} <- ActualClient.category_names(),
         {:ok, payees} <- ActualClient.payee_names() do
      accounts
      |> Enum.reject(& &1["offbudget"])
      |> Enum.reduce_while({:ok, []}, fn account, {:ok, acc} ->
        case ActualClient.transactions(account["id"], since_date, until_date) do
          {:ok, txns} ->
            mapped =
              Enum.map(txns, fn t ->
                %{
                  date: parse_date(t["date"]),
                  amount: t["amount"] || 0,
                  payee: payees[t["payee"]],
                  category: categories[t["category"]],
                  account: account["name"],
                  notes: t["notes"],
                  transfer: t["transfer_id"] != nil
                }
              end)

            {:cont, {:ok, acc ++ mapped}}

          {:error, reason} ->
            {:halt, {:error, reason}}
        end
      end)
    end
  end

  def format_money(cents) when is_integer(cents) do
    sign = if cents < 0, do: "-", else: ""
    abs_cents = abs(cents)
    "#{sign}$#{div(abs_cents, 100)}.#{abs_cents |> rem(100) |> Integer.to_string() |> String.pad_leading(2, "0")}"
  end

  def format_money(_), do: "$?"

  defp open_accounts do
    with {:ok, accounts} <- ActualClient.accounts() do
      {:ok, Enum.reject(accounts, & &1["closed"])}
    end
  end

  defp balances_for(accounts) do
    Enum.reduce_while(accounts, {:ok, []}, fn account, {:ok, acc} ->
      case ActualClient.balance(account["id"]) do
        {:ok, cents} when is_integer(cents) -> {:cont, {:ok, acc ++ [{account, cents}]}}
        {:ok, _} -> {:cont, {:ok, acc ++ [{account, 0}]}}
        {:error, reason} -> {:halt, {:error, reason}}
      end
    end)
  end

  defp sum_balances(balances), do: balances |> Enum.map(&elem(&1, 1)) |> Enum.sum()

  defp best_match(accounts, query) do
    q = String.downcase(query)

    Enum.find(accounts, fn a -> String.downcase(a["name"]) == q end) ||
      Enum.find(accounts, fn a -> String.contains?(String.downcase(a["name"]), q) end) ||
      Enum.find(accounts, fn a ->
        q |> String.split() |> Enum.all?(&String.contains?(String.downcase(a["name"]), &1))
      end)
  end

  defp parse_date(nil), do: nil

  defp parse_date(s) do
    case Date.from_iso8601(s) do
      {:ok, d} -> d
      _ -> nil
    end
  end
end
