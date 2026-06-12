defmodule WigglebotServer.Running.GarminClient do
  @moduledoc """
  Pulls activities from the Garmin Health/Wellness API into the runs table.

  Auth: set GARMIN_CLIENT_ID / GARMIN_CLIENT_SECRET and complete the
  one-time consent flow at GET /api/garmin/auth (see GarminAuth). Tokens
  are stored in the database and refreshed automatically. A static
  GARMIN_ACCESS_TOKEN env var is still honored as an override for testing.

  When unconfigured, sync_recent/1 is a logged no-op — Health Connect sync
  from the phone still populates runs.

  NOTE: endpoint shape follows the Garmin Wellness REST Activity API
  (`/wellness-api/rest/activities?uploadStartTimeInSeconds=...`); if your
  API tier differs, adjust `fetch_activities/2` accordingly.
  """

  require Logger

  alias WigglebotServer.Running
  alias WigglebotServer.Running.GarminAuth

  @running_types ~w(RUNNING TRAIL_RUNNING TREADMILL_RUNNING TRACK_RUNNING
                    INDOOR_RUNNING STREET_RUNNING ULTRA_RUN VIRTUAL_RUN)

  def configured? do
    static_token() not in [nil, ""] or (GarminAuth.configured?() and GarminAuth.connected?())
  end

  @doc "Pulls activities uploaded in the last `hours` hours (default 26)."
  def sync_recent(hours \\ 26) do
    if configured?() do
      now = System.os_time(:second)
      from_t = now - hours * 3600

      case fetch_activities(from_t, now) do
        {:ok, activities} ->
          results = Enum.map(activities, &store_activity/1)
          inserted = Enum.count(results, &(&1 == {:ok, :inserted}))
          Logger.info("Garmin sync: #{length(activities)} activities, #{inserted} new")
          {:ok, inserted}

        {:error, reason} ->
          Logger.warning("Garmin sync failed: #{reason}")
          {:error, reason}
      end
    else
      Logger.debug("Garmin not configured, skipping sync")
      :skipped
    end
  end

  defp fetch_activities(from_t, to_t) do
    base = Application.get_env(:wigglebot_server, :garmin_api_url, "https://apis.garmin.com")

    with {:ok, token} <- bearer_token() do
      url =
        "#{base}/wellness-api/rest/activities" <>
          "?uploadStartTimeInSeconds=#{from_t}&uploadEndTimeInSeconds=#{to_t}"

      case Req.get(url, auth: {:bearer, token}, receive_timeout: 30_000) do
        {:ok, %{status: 200, body: activities}} when is_list(activities) ->
          {:ok, activities}

        {:ok, %{status: status, body: body}} ->
          {:error, "Garmin API HTTP #{status}: #{inspect(body)}"}

        {:error, reason} ->
          {:error, "Garmin API unreachable: #{inspect(reason)}"}
      end
    end
  end

  defp bearer_token do
    case static_token() do
      token when is_binary(token) and token != "" -> {:ok, token}
      _ -> GarminAuth.access_token()
    end
  end

  defp static_token, do: Application.get_env(:wigglebot_server, :garmin_access_token)

  defp store_activity(activity) do
    type = activity["activityType"] || ""

    if String.upcase(type) in @running_types do
      started_at =
        DateTime.from_unix!(activity["startTimeInSeconds"] || 0)

      Running.upsert_run(%{
        source: "garmin",
        external_id: to_string(activity["summaryId"] || activity["activityId"]),
        started_at: started_at,
        duration_s: activity["durationInSeconds"],
        distance_m: to_float(activity["distanceInMeters"]),
        avg_hr: activity["averageHeartRateInBeatsPerMinute"],
        max_hr: activity["maxHeartRateInBeatsPerMinute"],
        elevation_gain_m: to_float(activity["totalElevationGainInMeters"]),
        calories: to_float(activity["activeKilocalories"]),
        title: activity["activityName"] || type
      })
    else
      {:ok, :skipped}
    end
  end

  defp to_float(nil), do: nil
  defp to_float(n) when is_integer(n), do: n * 1.0
  defp to_float(n) when is_float(n), do: n
  defp to_float(_), do: nil
end
