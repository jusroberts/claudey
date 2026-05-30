defmodule WigglebotServer.Metrolinx.Client do
  require Logger

  @base_url "https://api.openmetrolinx.com/OpenDataAPI/api/V1"
  @timeout 10_000

  @doc """
  Fetches service alerts for the Milton GO line (line code "MI").
  Returns {:ok, [text]} with one entry per alert, {:ok, []} if clear, or {:error, reason}.
  """
  def get_service_alerts do
    url = "#{@base_url}/ServiceUpdate/ServiceAlert/All?key=#{api_key()}"

    case Req.get(url, receive_timeout: @timeout) do
      {:ok, %{status: 200, body: body}} ->
        alerts =
          body
          |> get_in(["Messages", "Message"])
          |> List.wrap()
          |> Enum.filter(&milton_line?/1)
          |> Enum.map(&format_alert/1)
          |> Enum.reject(&(&1 == ""))

        {:ok, alerts}

      {:ok, %{status: status}} ->
        {:error, "Service alerts returned HTTP #{status}"}

      {:error, reason} ->
        {:error, inspect(reason)}
    end
  end

  @doc """
  Returns the next `count` direct Milton-line trains from `from_stop` to `to_stop`.
  Returns {:ok, [%{departs, arrives, is_tomorrow}]} or {:error, reason}.
  An empty list means no direct trains found for the rest of today or tomorrow morning.
  """
  def get_next_trains(from_stop, to_stop, count \\ 2) do
    {api_date, iso_date, time_str} = local_date_and_time()

    url =
      "#{@base_url}/Schedule/Journey/#{api_date}/#{from_stop}/#{to_stop}/#{time_str}/#{count + 2}?key=#{api_key()}"

    case Req.get(url, receive_timeout: @timeout) do
      {:ok, %{status: 200, body: body}} ->
        trains =
          body
          |> get_in(["SchJourneys", Access.at(0), "Services"])
          |> List.wrap()
          |> Enum.filter(&direct_mi_train?/1)
          |> Enum.take(count)
          |> Enum.map(fn svc ->
            %{
              departs: format_time(svc["StartTime"]),
              arrives: format_time(svc["EndTime"]),
              is_tomorrow: not String.starts_with?(svc["StartTime"], iso_date)
            }
          end)

        {:ok, trains}

      {:ok, %{status: status}} ->
        {:error, "Schedule API returned HTTP #{status}"}

      {:error, reason} ->
        {:error, inspect(reason)}
    end
  end

  # ── Private ───────────────────────────────────────────────────────────────────

  defp api_key, do: Application.get_env(:wigglebot_server, :metrolinx_api_key, "")

  defp local_date_and_time do
    {{y, m, d}, {h, min, _}} = :calendar.local_time()
    api_date = :io_lib.format("~4..0B~2..0B~2..0B", [y, m, d]) |> to_string()
    iso_date = :io_lib.format("~4..0B-~2..0B-~2..0B", [y, m, d]) |> to_string()
    time_str = :io_lib.format("~2..0B~2..0B", [h, min]) |> to_string()
    {api_date, iso_date, time_str}
  end

  defp milton_line?(%{"Lines" => lines}) when is_list(lines),
    do: Enum.any?(lines, &(&1["Code"] == "MI"))

  defp milton_line?(_), do: false

  defp direct_mi_train?(svc) do
    svc["transferCount"] == 0 and
      svc
      |> get_in(["Trips", "Trip"])
      |> List.wrap()
      |> Enum.all?(&(&1["Type"] == "T" and &1["Line"] == "MI"))
  end

  # "2026-04-23 07:45:00" → "7:45am"
  defp format_time(dt_str) when is_binary(dt_str) do
    case String.split(dt_str, " ") do
      [_date, time_part] ->
        case String.split(time_part, ":") do
          [h_str, m_str | _] ->
            h = String.to_integer(h_str)
            m = String.to_integer(m_str)
            period = if h < 12, do: "am", else: "pm"
            display_h = cond do
              h == 0 -> 12
              h > 12 -> h - 12
              true -> h
            end
            :io_lib.format("~B:~2..0B~s", [display_h, m, period]) |> to_string()

          _ -> dt_str
        end

      _ -> dt_str
    end
  end

  defp format_alert(msg) do
    subject = msg["SubjectEnglish"] || ""
    body = msg["BodyEnglish"] || ""
    blurb = body |> String.split(~r/[.]\s/, parts: 2) |> List.first("") |> String.trim()

    cond do
      blurb != "" and blurb != subject -> "#{subject}: #{blurb}"
      subject != "" -> subject
      true -> ""
    end
  end
end
