defmodule WigglebotServer.Tools.ServerTools do
  @moduledoc "Executes server-side tools (weather, transit, nearby)."

  require Logger

  def execute("get_weather", args, location_fn) do
    location = Map.get(args, "location")
    fetch_weather(location, location_fn)
  end

  def execute("find_nearby", args, location_fn) do
    type = Map.get(args, "type", "restaurant")
    location = Map.get(args, "location")
    radius = Map.get(args, "radius_meters", "5000") |> parse_int(5000)
    fetch_nearby(type, radius, location, location_fn)
  end

  def execute("get_transit", args, location_fn) do
    destination = Map.get(args, "destination")
    origin = Map.get(args, "origin")
    fetch_transit(destination, origin, location_fn)
  end

  def execute("navigate_to", args, _location_fn) do
    destination = Map.get(args, "destination", "")
    encoded = URI.encode(destination)
    {:ok, "navigate_to:#{encoded}"}
  end

  def execute(name, _args, _location_fn) do
    {:error, "Unknown server tool: #{name}"}
  end

  # ── Weather ─────────────────────────────────────────────────────────────────

  defp fetch_weather(location_name, location_fn \\ nil) do
    with {:ok, {lat, lon}} <- resolve_lat_lon(location_name, location_fn),
         url =
           "https://api.open-meteo.com/v1/forecast" <>
             "?latitude=#{lat}&longitude=#{lon}" <>
             "&current=temperature_2m,apparent_temperature,precipitation,weathercode,windspeed_10m" <>
             "&temperature_unit=celsius&windspeed_unit=kmh&timezone=auto",
         {:ok, %{status: 200, body: body}} <- Req.get(url) do
      c = body["current"]
      label = location_name || "your location"

      result =
        "Weather in #{label}: #{round(c["temperature_2m"])}°C, #{weather_desc(c["weathercode"])}. " <>
          "Feels like #{round(c["apparent_temperature"])}°C. " <>
          "Wind #{round(c["windspeed_10m"])} km/h." <>
          if(c["precipitation"] > 0, do: " Precipitation: #{c["precipitation"]} mm.", else: "")

      {:ok, result}
    else
      {:error, reason} -> {:error, reason}
      _ -> {:error, "Weather fetch failed"}
    end
  end

  # ── Nearby ───────────────────────────────────────────────────────────────────

  defp fetch_nearby(type, radius, location_name, location_fn) do
    with {:ok, {lat, lon}} <- resolve_lat_lon(location_name, location_fn) do
      amenity =
        case type do
          "restaurant" -> "restaurant|fast_food|food_court"
          "gas_station" -> "fuel"
          "coffee" -> "cafe"
          "parking" -> "parking"
          "pharmacy" -> "pharmacy"
          "supermarket" -> "supermarket|convenience"
          _ -> "restaurant"
        end

      query =
        "[out:json][timeout:10];" <>
          "node(around:#{radius},#{lat},#{lon})[amenity~\"^(#{amenity})$\"];" <>
          "out 8;"

      case Req.post("https://overpass-api.de/api/interpreter", form: [data: query]) do
        {:ok, %{status: 200, body: %{"elements" => elements}}} ->
          label = location_name || "your location"
          type_label = String.replace(type, "_", " ") |> String.capitalize()

          results =
            elements
            |> Enum.filter(&(&1["tags"]["name"] && &1["lat"] && &1["lon"]))
            |> Enum.take(5)
            |> Enum.with_index(1)
            |> Enum.map(fn {el, i} ->
              dist = haversine_miles(lat, lon, el["lat"], el["lon"])
              "#{i}. #{el["tags"]["name"]} (#{Float.round(dist, 1)} mi)"
            end)

          if results == [] do
            {:ok, "No named #{type_label}s found near #{label}."}
          else
            {:ok, "#{type_label}s near #{label}: #{Enum.join(results, ", ")}"}
          end

        _ ->
          {:error, "Nearby search failed"}
      end
    end
  end

  # ── Transit ──────────────────────────────────────────────────────────────────

  defp fetch_transit(destination, origin_override, location_fn) do
    api_key = Application.get_env(:wigglebot_server, :google_maps_api_key, "")

    if api_key == "" do
      {:error, "No Google Maps API key configured."}
    else
      vague = ~w[current location my location here current position gps]

      origin =
        if origin_override && String.downcase(origin_override) not in vague do
          origin_override
        else
          case location_fn.() do
            {:ok, {lat, lon}} -> "#{lat},#{lon}"
            _ -> nil
          end
        end

      if is_nil(origin) do
        {:error, "Location unavailable — cannot determine origin."}
      else
        departure_time = System.os_time(:second)

        url =
          "https://maps.googleapis.com/maps/api/directions/json" <>
            "?origin=#{URI.encode(origin)}" <>
            "&destination=#{URI.encode(destination)}" <>
            "&mode=transit" <>
            "&departure_time=#{departure_time}" <>
            "&key=#{api_key}"

        case Req.get(url) do
          {:ok, %{status: 200, body: body}} ->
            parse_transit_response(body, destination, origin_override)

          _ ->
            {:error, "Transit fetch failed"}
        end
      end
    end
  end

  defp parse_transit_response(%{"status" => "OK", "routes" => [route | _]}, destination, _origin) do
    leg = route["legs"] |> List.first()
    total = leg["duration"]["text"]
    arrival = get_in(leg, ["arrival_time", "text"]) || "unknown"

    transit_steps =
      leg["steps"]
      |> Enum.filter(&(&1["travel_mode"] == "TRANSIT"))

    if transit_steps == [] do
      {:error, "No transit steps found in route."}
    else
      summary =
        transit_steps
        |> Enum.with_index()
        |> Enum.map_join(", ", fn {step, i} ->
          td = step["transit_details"]
          line = td["line"]
          line_name = line["short_name"] || line["name"] || line["vehicle"]["name"]
          vehicle = line["vehicle"]["name"]
          dep_stop = td["departure_stop"]["name"]
          dep_time = td["departure_time"]["text"]
          stops = td["num_stops"] || 0

          if i == 0 do
            "Take #{vehicle} \"#{line_name}\" from #{dep_stop} at #{dep_time}" <>
              if(stops > 0, do: " — #{stops} stops", else: "")
          else
            "transfer to #{vehicle} \"#{line_name}\" at #{dep_stop} (#{dep_time})" <>
              if(stops > 0, do: " — #{stops} stops", else: "")
          end
        end)

      last_td = List.last(transit_steps)["transit_details"]
      arr_stop = last_td["arrival_stop"]["name"]

      {:ok,
       "Trip to #{destination} (#{total}): #{summary}, arrive #{arr_stop} at #{arrival}."}
    end
  end

  defp parse_transit_response(%{"status" => "ZERO_RESULTS"}, destination, origin) do
    {:error, "No transit routes found from #{origin || "your location"} to #{destination}."}
  end

  defp parse_transit_response(%{"status" => status, "error_message" => msg}, _, _) do
    {:error, "Transit lookup failed: #{status} — #{msg}"}
  end

  defp parse_transit_response(%{"status" => status}, _, _) do
    {:error, "Transit lookup failed: #{status}"}
  end

  # ── Geocoding ────────────────────────────────────────────────────────────────

  defp resolve_lat_lon(nil, location_fn) when is_function(location_fn) do
    location_fn.()
  end

  defp resolve_lat_lon(nil, _), do: {:error, "No location available"}

  defp resolve_lat_lon(name, _) do
    url = "https://nominatim.openstreetmap.org/search?q=#{URI.encode(name)}&format=json&limit=1"

    case Req.get(url, headers: [{"user-agent", "WiggleBotServer/1.0"}]) do
      {:ok, %{status: 200, body: [%{"lat" => lat, "lon" => lon} | _]}} ->
        {:ok, {String.to_float(lat), String.to_float(lon)}}

      _ ->
        {:error, "Could not find location \"#{name}\""}
    end
  end

  # ── Helpers ──────────────────────────────────────────────────────────────────

  defp haversine_miles(lat1, lon1, lat2, lon2) do
    r = 3958.8
    d_lat = :math.pi() / 180 * (lat2 - lat1)
    d_lon = :math.pi() / 180 * (lon2 - lon1)

    a =
      :math.sin(d_lat / 2) ** 2 +
        :math.cos(:math.pi() / 180 * lat1) *
          :math.cos(:math.pi() / 180 * lat2) *
          :math.sin(d_lon / 2) ** 2

    r * 2 * :math.asin(:math.sqrt(a))
  end

  defp parse_int(val, default) when is_binary(val) do
    case Integer.parse(val) do
      {n, _} -> n
      :error -> default
    end
  end

  defp parse_int(val, _) when is_integer(val), do: val
  defp parse_int(_, default), do: default

  defp weather_desc(code) do
    case code do
      0 -> "clear sky"
      c when c in [1, 2] -> "partly cloudy"
      3 -> "overcast"
      c when c in [45, 48] -> "foggy"
      c when c in [51, 53, 55] -> "drizzle"
      c when c in [61, 63, 65] -> "rain"
      c when c in [66, 67] -> "freezing rain"
      c when c in [71, 73, 75] -> "snow"
      77 -> "snow grains"
      c when c in [80, 81, 82] -> "rain showers"
      c when c in [85, 86] -> "snow showers"
      95 -> "thunderstorm"
      c when c in [96, 99] -> "thunderstorm with hail"
      _ -> "mixed conditions"
    end
  end
end
