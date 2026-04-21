defmodule WigglebotServer.ParkBooking do
  require Logger

  @park_api "https://admin.parkpassproject.com/api"
  @inventory_group 1554186518
  @discount_group_id 1

  def book(inventory_id, date \\ Date.utc_today()) do
    cfg = Application.get_env(:wigglebot_server, :park_booking, [])
    membership_code = cfg[:membership_code] || ""
    name            = cfg[:name] || ""
    email           = cfg[:email] || ""
    phone           = cfg[:phone] || ""
    license_plate   = cfg[:license_plate] || ""
    postal_code     = cfg[:postal_code] || ""

    if membership_code == "" do
      {:error, "PARK_MEMBERSHIP not configured on server"}
    else
      with :ok <- validate_membership(membership_code, inventory_id, date) do
        reserve(inventory_id, membership_code, name, email, phone,
                license_plate, postal_code, date)
      end
    end
  end

  defp validate_membership(code, inventory_id, date) do
    start_dt = "#{date} 08:30"
    url = "#{@park_api}/memberValidate/" <>
      "?code=#{code}&inventory=#{inventory_id}" <>
      "&start_date_time=#{URI.encode(start_dt)}&discount_group=ch_member"

    case Req.get(url, receive_timeout: 10_000) do
      {:ok, %{status: 200, body: %{"profile" => %{"first_name" => _}}}} ->
        :ok

      {:ok, %{status: status, body: body}} ->
        Logger.warning("memberValidate failed: HTTP #{status} #{inspect(body)}")
        {:error, "Membership validation failed"}

      {:error, reason} ->
        Logger.warning("memberValidate request error: #{inspect(reason)}")
        {:error, "Could not reach booking server"}
    end
  end

  defp reserve(inventory_id, membership_code, name, email, phone,
               license_plate, postal_code, date) do
    now = DateTime.utc_now()
    date_str   = Calendar.strftime(now, "%Y-%m-%d %H:%M:%S")
    eff_date   = Calendar.strftime(now, "%Y-%m-%d %H:%M")
    start_date = "#{date} 08:30:00"
    end_date   = "#{date} 21:00:00"

    body = %{
      "tempReservationId" => "",
      "reserve"           => false,
      "g-recaptcha-response" => "",
      "paymentMethod"     => "",
      "gift_card_code"    => nil,
      "gift_card_amount"  => nil,
      "reservation"       => %{
        "inventory_group"       => @inventory_group,
        "feeder_inventory"      => nil,
        "inventory"             => inventory_id,
        "date"                  => date_str,
        "contact"               => %{"name" => name, "email" => email, "phone" => phone},
        "passes"                => [
          %{
            "id" => 3, "count" => 1, "inventory" => inventory_id,
            "start_date" => start_date, "end_date" => end_date,
            "visitor_type" => 3, "adds_occupancy" => true, "visitor_count" => 1,
            "price" => 0, "price_before_tax" => 0, "unit_price" => 0,
            "unit_price_before_tax" => 0, "price_rules" => [], "vehicles" => [],
            "type" => "D", "tax_type" => 1, "tax_breakdown" => %{}
          },
          %{
            "id" => 2, "count" => 1, "inventory" => inventory_id,
            "start_date" => start_date, "end_date" => end_date,
            "visitor_type" => 2, "adds_occupancy" => true, "visitor_count" => 1,
            "price" => 0, "price_before_tax" => 0, "unit_price" => 0,
            "unit_price_before_tax" => 0, "price_rules" => [],
            "vehicles" => [%{"license_plate" => license_plate}],
            "type" => "D", "tax_type" => 1, "tax_breakdown" => %{}
          }
        ],
        "addons"                => [],
        "price"                 => 0, "subtotal" => 0,
        "tax_amount"            => 0, "donation" => 0,
        "reservation_type"      => 4,
        "reservation_type_name" => "person_fee_car_limit",
        "vehicles"              => [%{"license_plate" => license_plate}],
        "discount_group"        => @discount_group_id,
        "start_date"            => start_date, "end_date" => end_date,
        "profiles"              => [], "discounts" => [], "seats" => [],
        "location"              => %{"postal_code" => postal_code},
        "billingAddress"        => %{"postal_code" => postal_code},
        "details"               => %{
          "language"        => %{"id" => 1, "name" => "English", "short" => "EN", "order" => 0},
          "genericCheckbox" => false,
          "mainMembership"  => membership_code,
          "custom_fields"   => %{},
          "effective_date"  => eff_date,
          "subscribed2"     => false
        },
        "tax_breakdown" => %{},
        "waitlist"      => false
      }
    }

    case Req.post("#{@park_api}/reserve", json: body, receive_timeout: 15_000) do
      {:ok, %{status: 200, body: %{"reservation" => id}}} ->
        Logger.info("Park booking created: #{id}")
        {:ok, id}

      {:ok, %{status: status, body: body}} ->
        Logger.warning("reserve failed: HTTP #{status} #{inspect(body)}")
        {:error, "Booking failed (HTTP #{status})"}

      {:error, reason} ->
        Logger.warning("reserve request error: #{inspect(reason)}")
        {:error, "Could not reach booking server"}
    end
  end
end
