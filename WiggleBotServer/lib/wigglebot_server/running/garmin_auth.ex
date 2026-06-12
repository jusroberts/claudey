defmodule WigglebotServer.Running.GarminAuth do
  @moduledoc """
  Garmin Health API OAuth2 (PKCE) token management.

  One-time setup with your GARMIN_CLIENT_ID / GARMIN_CLIENT_SECRET:
    1. Register `https://<server>/api/garmin/callback` as the redirect URI
       in your Garmin developer app settings.
    2. Visit `https://<server>/api/garmin/auth` in a browser on the
       tailnet, log into Garmin, approve.
  The callback stores the access+refresh tokens in the oauth_tokens table.
  Access tokens expire after ~24h; `access_token/0` refreshes (and persists
  the rotated refresh token) automatically.

  Endpoint URLs follow Garmin's current OAuth2 docs; adjust the module
  attributes if your program's docs differ.
  """

  import Ecto.Query

  require Logger

  alias WigglebotServer.Repo
  alias WigglebotServer.Running.OauthToken

  @provider "garmin"
  @authorize_url "https://connect.garmin.com/oauth2Confirm"
  @token_url "https://diauth.garmin.com/di-oauth2-service/oauth/token"
  # refresh slightly early so in-flight requests don't race expiry
  @expiry_slack_s 300

  def configured? do
    client_id() not in [nil, ""] and client_secret() not in [nil, ""]
  end

  def connected? do
    Repo.exists?(from t in OauthToken, where: t.provider == @provider)
  end

  @doc "Builds the consent URL and stashes the PKCE verifier for the callback."
  def authorize_url(redirect_uri) do
    verifier =
      :crypto.strong_rand_bytes(64) |> Base.url_encode64(padding: false)

    challenge =
      :crypto.hash(:sha256, verifier) |> Base.url_encode64(padding: false)

    :persistent_term.put({__MODULE__, :pkce}, {verifier, redirect_uri})

    @authorize_url <>
      "?" <>
      URI.encode_query(
        response_type: "code",
        client_id: client_id(),
        redirect_uri: redirect_uri,
        code_challenge: challenge,
        code_challenge_method: "S256"
      )
  end

  @doc "Exchanges the authorization code from the callback and stores tokens."
  def exchange_code(code) do
    case :persistent_term.get({__MODULE__, :pkce}, nil) do
      nil ->
        {:error, "no pending authorization — start at /api/garmin/auth"}

      {verifier, redirect_uri} ->
        request_token(
          grant_type: "authorization_code",
          client_id: client_id(),
          client_secret: client_secret(),
          code: code,
          code_verifier: verifier,
          redirect_uri: redirect_uri
        )
    end
  end

  @doc "Returns a valid access token, refreshing it when expired."
  def access_token do
    case Repo.get_by(OauthToken, provider: @provider) do
      nil ->
        {:error, "Garmin not connected — visit /api/garmin/auth"}

      %OauthToken{} = token ->
        if expired?(token) do
          refresh(token)
        else
          {:ok, token.access_token}
        end
    end
  end

  defp expired?(%{expires_at: nil}), do: true

  defp expired?(%{expires_at: expires_at}) do
    DateTime.diff(expires_at, DateTime.utc_now()) < @expiry_slack_s
  end

  defp refresh(token) do
    request_token(
      grant_type: "refresh_token",
      client_id: client_id(),
      client_secret: client_secret(),
      refresh_token: token.refresh_token
    )
  end

  defp request_token(form) do
    case Req.post(@token_url, form: form, receive_timeout: 30_000) do
      {:ok, %{status: 200, body: %{"access_token" => access} = body}} ->
        store_tokens(access, body["refresh_token"], body["expires_in"])
        {:ok, access}

      {:ok, %{status: status, body: body}} ->
        Logger.warning("Garmin token request failed: HTTP #{status} #{inspect(body)}")
        {:error, "Garmin token request failed (HTTP #{status})"}

      {:error, reason} ->
        {:error, "Garmin token endpoint unreachable: #{inspect(reason)}"}
    end
  end

  defp store_tokens(access, refresh, expires_in) do
    expires_at =
      DateTime.utc_now()
      |> DateTime.add(expires_in || 3600)
      |> DateTime.truncate(:second)

    existing = Repo.get_by(OauthToken, provider: @provider)

    attrs = %{
      provider: @provider,
      access_token: access,
      # Garmin rotates refresh tokens; keep the old one if none returned.
      refresh_token: refresh || (existing && existing.refresh_token),
      expires_at: expires_at
    }

    case existing do
      nil -> %OauthToken{} |> OauthToken.changeset(attrs) |> Repo.insert!()
      token -> token |> OauthToken.changeset(attrs) |> Repo.update!()
    end
  end

  defp client_id, do: Application.get_env(:wigglebot_server, :garmin_client_id)
  defp client_secret, do: Application.get_env(:wigglebot_server, :garmin_client_secret)
end

defmodule WigglebotServer.Running.OauthToken do
  use Ecto.Schema

  import Ecto.Changeset

  schema "oauth_tokens" do
    field :provider, :string
    field :access_token, :string
    field :refresh_token, :string
    field :expires_at, :utc_datetime

    timestamps(type: :utc_datetime)
  end

  def changeset(token, attrs) do
    token
    |> cast(attrs, [:provider, :access_token, :refresh_token, :expires_at])
    |> validate_required([:provider, :access_token])
    |> unique_constraint(:provider)
  end
end
