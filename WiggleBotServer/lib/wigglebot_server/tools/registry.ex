defmodule WigglebotServer.Tools.Registry do
  @moduledoc """
  Declares all tools and whether each runs server-side or must be
  delegated to the Android device.
  """

  @tools [
    # ── Device-side (Android executes these) ─────────────────────────────────
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_play_pause",
        description: "Toggle play/pause for whatever is currently active in a media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_next_track",
        description: "Skip to the next track in the active media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "media_previous_track",
        description: "Go back to the previous track in the active media app.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "spotify_search_play",
        description: "Open Spotify and search for a song, album, artist, or playlist.",
        parameters: %{
          type: "object",
          properties: %{
            query: %{type: "string", description: "Search query"},
            type: %{
              type: "string",
              description: "Result type",
              enum: ["track", "album", "artist", "playlist"]
            }
          },
          required: ["query"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "audible_open",
        description: "Open the Audible app.",
        parameters: %{
          type: "object",
          properties: %{
            title: %{type: "string", description: "Optional audiobook title to search for"}
          }
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "launch_app",
        description: "Open any installed app by its common name.",
        parameters: %{
          type: "object",
          properties: %{
            app_name: %{type: "string", description: "Common name of the app"}
          },
          required: ["app_name"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "open_url",
        description: "Open a URL in the default browser.",
        parameters: %{
          type: "object",
          properties: %{
            url: %{type: "string", description: "Full URL including scheme"}
          },
          required: ["url"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "set_volume",
        description: "Set the media volume on the device.",
        parameters: %{
          type: "object",
          properties: %{
            level: %{
              type: "string",
              description: "Volume level: 'mute', 'low', 'medium', 'high', or '50%'"
            }
          },
          required: ["level"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "send_notification",
        description: "Post a local notification to the user.",
        parameters: %{
          type: "object",
          properties: %{
            title: %{type: "string", description: "Notification title"},
            body: %{type: "string", description: "Notification body text"}
          },
          required: ["title", "body"]
        }
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "get_installed_apps",
        description: "Returns a list of installed apps on the device.",
        parameters: %{type: "object", properties: %{}}
      }
    },
    %{
      side: :device,
      type: "function",
      function: %{
        name: "get_location",
        description: "Returns the device's current GPS coordinates.",
        parameters: %{type: "object", properties: %{}}
      }
    },

    # ── Server-side ───────────────────────────────────────────────────────────
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_weather",
        description:
          "Get current weather conditions. Defaults to device GPS location. Pass a city name to check elsewhere.",
        parameters: %{
          type: "object",
          properties: %{
            location: %{
              type: "string",
              description: "Optional city or place name. Omit to use device GPS."
            }
          }
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "find_nearby",
        description: "Find nearby places using OpenStreetMap.",
        parameters: %{
          type: "object",
          properties: %{
            type: %{
              type: "string",
              enum: ["restaurant", "gas_station", "coffee", "parking", "pharmacy", "supermarket"]
            },
            location: %{type: "string", description: "Optional place name. Omit for device GPS."},
            radius_meters: %{type: "string", description: "Search radius. Default 5000."}
          },
          required: ["type"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "get_transit",
        description: "Get transit directions using Google Maps.",
        parameters: %{
          type: "object",
          properties: %{
            destination: %{type: "string", description: "Where to go"},
            origin: %{
              type: "string",
              description: "Starting point. Omit entirely to use device GPS."
            }
          },
          required: ["destination"]
        }
      }
    },
    %{
      side: :server,
      type: "function",
      function: %{
        name: "navigate_to",
        description: "Start turn-by-turn navigation via Waze or Google Maps.",
        parameters: %{
          type: "object",
          properties: %{
            destination: %{type: "string", description: "Destination address or place name"}
          },
          required: ["destination"]
        }
      }
    }
  ]

  def all, do: @tools

  def for_llama do
    Enum.map(@tools, &Map.drop(&1, [:side]))
  end

  def side(tool_name) do
    case Enum.find(@tools, &(&1.function.name == tool_name)) do
      %{side: side} -> side
      nil -> :unknown
    end
  end
end
